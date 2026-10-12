package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.ai.AiConfig;
import com.sclera.applicationplane.procedure.ai.DocumentExtractionService;
import com.sclera.applicationplane.procedure.domain.AiDocument;
import com.sclera.applicationplane.procedure.domain.AiDocumentStatus;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.AiDocumentResponse;
import com.sclera.applicationplane.procedure.mapper.AiMapper;
import com.sclera.applicationplane.procedure.repository.AiDocumentRepository;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.exception.ValidationException;
import com.sclera.controlplane.common.security.OrgContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * The organization's document library: uploads are stored once, extracted once,
 * and kept for reference so questions can be re-generated from the same source
 * later without re-uploading. Generation itself never lives here — this service
 * owns only the document lifecycle (store → extract → READY/FAILED → list).
 */
@Service
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class AiDocumentService {

    private static final Logger log = LoggerFactory.getLogger(AiDocumentService.class);

    private final AiDocumentRepository documents;
    private final DocumentExtractionService extraction;
    private final AiMapper aiMapper;
    private final TaskExecutor executor;

    public AiDocumentService(AiDocumentRepository documents,
                             DocumentExtractionService extraction,
                             AiMapper aiMapper,
                             @Qualifier(AiConfig.EXECUTOR) TaskExecutor executor) {
        this.documents = documents;
        this.extraction = extraction;
        this.aiMapper = aiMapper;
        this.executor = executor;
    }

    /** Stores the upload and extracts its text in the background (PENDING → READY). */
    public AiDocumentResponse upload(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (file.isEmpty()) {
            throw new ValidationException("The uploaded file is empty");
        }
        if (!DocumentExtractionService.isSupported(filename)) {
            throw new ValidationException("Unsupported document type: '" + filename
                    + "'. Upload a PDF, DOCX or TXT file.");
        }

        AiDocument document = new AiDocument();
        UUID orgId = OrgContext.getOrgId();
        document.setOrgId(orgId);
        document.setFilename(filename);
        document.setMediaType(file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        document.setSizeBytes(file.getSize());
        try {
            document.setContent(file.getBytes());
        } catch (IOException e) {
            throw new ValidationException("Could not read the uploaded file: " + e.getMessage());
        }
        document.setCreatedBy(OrgContext.getUserId());
        documents.saveAndFlush(document);

        UUID documentId = document.getId();
        executor.execute(() -> extractInBackground(documentId, orgId));
        return aiMapper.toResponse(document);
    }

    public AiDocumentResponse getDocument(UUID id) {
        return aiMapper.toResponse(ownedDocument(id));
    }

    /** Every document the organization has uploaded, newest first — the reference library. */
    public List<AiDocumentResponse> list() {
        return documents.findAllByOrgIdOrderByCreatedAtDesc(OrgContext.getOrgId()).stream()
                .map(aiMapper::toResponse)
                .toList();
    }

    private void extractInBackground(UUID documentId, UUID orgId) {
        OrgContext.clear();
        OrgContext.setOrgId(orgId);
        try {
            AiDocument document = documents.findByIdAndOrgId(documentId, orgId).orElseThrow();
            document.setStatus(AiDocumentStatus.EXTRACTING);
            documents.saveAndFlush(document);
            try {
                String text = extraction.extract(document.getFilename(), document.getContent());
                document.setExtractedText(text);
                document.setStatus(AiDocumentStatus.READY);
                document.setError(null);
            } catch (Exception e) {
                log.warn("Extraction failed for document {}: {}", documentId, e.getMessage());
                document.setStatus(AiDocumentStatus.FAILED);
                String error = e.getMessage() == null ? "Extraction failed" : e.getMessage();
                document.setError(error.length() <= 2000 ? error : error.substring(0, 2000));
            }
            documents.saveAndFlush(document);
        } finally {
            OrgContext.clear();
        }
    }

    private AiDocument ownedDocument(UUID id) {
        return documents.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + id));
    }
}
