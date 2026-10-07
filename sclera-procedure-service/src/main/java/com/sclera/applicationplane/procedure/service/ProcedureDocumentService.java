package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.client.storage.StorageClient;
import com.sclera.applicationplane.procedure.domain.ProcedureDocument;
import com.sclera.applicationplane.procedure.dto.ProcedureDocumentRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureDocumentResponse;
import com.sclera.applicationplane.procedure.mapper.ProcedureDocumentMapper;
import com.sclera.applicationplane.procedure.repository.ProcedureDocumentRepository;
import com.sclera.applicationplane.procedure.tenancy.PropertyContext;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The organization's (or one property's) library of reference documents.
 * Bytes live behind the helper service's storage port; this manages only the
 * metadata row and which procedures may cite it.
 */
@Service
@Transactional
public class ProcedureDocumentService {

    private final ProcedureDocumentRepository repository;
    private final ProcedureDocumentMapper mapper;
    private final StorageClient storage;

    public ProcedureDocumentService(ProcedureDocumentRepository repository, ProcedureDocumentMapper mapper,
                                     StorageClient storage) {
        this.repository = repository;
        this.mapper = mapper;
        this.storage = storage;
    }

    /**
     * The bytes are already stored by the time this is called — upload goes
     * straight to the helper service. This writes the library row, after
     * confirming the location the caller sends back actually resolves to
     * something, so a mistyped or expired location cannot be cited by a
     * procedure that will fail to resolve it later.
     */
    public ProcedureDocumentResponse create(ProcedureDocumentRequest request) {
        if (!storage.resolves(request.location())) {
            throw new BusinessRuleException(
                    "No stored document found at this location; it may not have finished uploading");
        }

        ProcedureDocument document = new ProcedureDocument();
        document.setOrgId(OrgContext.getOrgId());
        // Same rule as a procedure's own scope: exactly one property in scope
        // means this was authored standing inside it, and anything else means
        // organization level and shared.
        List<UUID> scope = PropertyContext.current();
        document.setPropertyId(scope.size() == 1 ? scope.get(0) : null);
        document.setName(request.name().strip());
        document.setMimeType(request.mimeType());
        document.setSizeBytes(request.sizeBytes());
        document.setLocation(request.location());
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

    /**
     * Stops the document being offered for new citations while every version
     * that already cites it keeps resolving it. The guard that refuses an
     * outright delete once a published version cites a document arrives with
     * {@code version_document_ref}; until then this is the only way back from
     * a mistaken upload.
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

    private ProcedureDocument getOwned(UUID id) {
        return repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + id));
    }
}
