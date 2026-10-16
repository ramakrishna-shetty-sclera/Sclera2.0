package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.domain.ProcedureDocument;
import com.sclera.applicationplane.procedure.dto.ProcedureDocumentResponse;
import com.sclera.applicationplane.procedure.mapper.ProcedureDocumentMapper;
import com.sclera.applicationplane.procedure.repository.ProcedureDocumentRepository;
import com.sclera.applicationplane.procedure.repository.VersionDocumentRefRepository;
import com.sclera.applicationplane.procedure.tenancy.PropertyContext;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.exception.ValidationException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The organization's (or one property's) library of reference documents.
 * Bytes live directly on the row — the same way {@code ai_document} already
 * stores an AI source document — no second service, no Dapr call.
 */
@Service
@Transactional
public class ProcedureDocumentService {

    private final ProcedureDocumentRepository repository;
    private final ProcedureDocumentMapper mapper;
    private final VersionDocumentRefRepository refs;

    public ProcedureDocumentService(ProcedureDocumentRepository repository, ProcedureDocumentMapper mapper,
                                     VersionDocumentRefRepository refs) {
        this.repository = repository;
        this.mapper = mapper;
        this.refs = refs;
    }

    /**
     * Stores the uploaded bytes directly on the new row, mirroring {@code
     * AiDocumentService.upload} — no file-type restriction, unlike AI
     * documents, since a reference document is never text-extracted.
     */
    public ProcedureDocumentResponse upload(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ValidationException("The uploaded file is empty");
        }

        ProcedureDocument document = new ProcedureDocument();
        document.setOrgId(OrgContext.getOrgId());
        // Same rule as a procedure's own scope: exactly one property in scope
        // means this was authored standing inside it, and anything else means
        // organization level and shared.
        List<UUID> scope = PropertyContext.current();
        document.setPropertyId(scope.size() == 1 ? scope.get(0) : null);
        document.setName(file.getOriginalFilename());
        document.setMimeType(file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        document.setSizeBytes(file.getSize());
        try {
            document.setContent(file.getBytes());
        } catch (IOException e) {
            throw new ValidationException("Could not read the uploaded file: " + e.getMessage());
        }
        document.setUploadedBy(OrgContext.getUserId());
        document.setUploadedAt(OffsetDateTime.now());

        return mapper.toResponse(repository.save(document));
    }

    /** Ordered by name. {@code active} null returns both active and inactive. */
    @Transactional(readOnly = true)
    public List<ProcedureDocumentResponse> list(Boolean active) {
        UUID orgId = OrgContext.getOrgId();
        List<ProcedureDocument> found = active == null
                ? repository.findAllByOrgIdOrderByNameAsc(orgId)
                : repository.findAllByOrgIdAndActiveOrderByNameAsc(orgId, active);
        return found.stream().map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProcedureDocumentResponse get(UUID id) {
        return mapper.toResponse(getOwned(id));
    }

    /** The row itself, for the content endpoint — name, mime type and bytes, never serialised into a JSON response. */
    @Transactional(readOnly = true)
    public ProcedureDocument getForContent(UUID id) {
        return getOwned(id);
    }

    /**
     * Stops the document being offered for new citations while every version
     * that already cites it keeps resolving it. This is the escape hatch
     * {@link #delete} points at once a published version cites the document,
     * since deleting it outright is refused from that point on.
     */
    public ProcedureDocumentResponse deactivate(UUID id) {
        ProcedureDocument document = getOwned(id);
        document.setActive(false);
        return mapper.toResponse(document);
    }

    public ProcedureDocumentResponse activate(UUID id) {
        ProcedureDocument document = getOwned(id);
        document.setActive(true);
        return mapper.toResponse(document);
    }

    /**
     * Hard delete, refused once any published version cites the document:
     * that version is the record of what a past inspection's evidence pointed
     * at, and deleting the document it cites would leave that record pointing
     * at nothing. Deactivating is the way out, and the refusal says so — the
     * same shape as {@code ResultTypeService.delete}'s guard, for the same
     * reason.
     */
    public void delete(UUID id) {
        ProcedureDocument document = getOwned(id);
        // Counts every published version in the organization, whatever
        // property it belongs to: the index carries no row-level security.
        long usedBy = refs.countByDocumentId(id);
        if (usedBy > 0) {
            throw new BusinessRuleException(document.getName() + " is used by " + usedBy
                    + (usedBy == 1 ? " published version" : " published versions")
                    + ", so it cannot be deleted; deactivate it instead");
        }
        repository.delete(document);
    }

    private ProcedureDocument getOwned(UUID id) {
        return repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + id));
    }
}
