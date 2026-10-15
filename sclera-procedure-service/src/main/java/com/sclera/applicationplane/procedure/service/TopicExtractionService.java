package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.ai.AiConfig;
import com.sclera.applicationplane.procedure.ai.AiProperties;
import com.sclera.applicationplane.procedure.ai.DocumentExtractionService;
import com.sclera.applicationplane.procedure.ai.LlmClient;
import com.sclera.applicationplane.procedure.ai.LlmSchemas;
import com.sclera.applicationplane.procedure.ai.PromptLoader;
import com.sclera.applicationplane.procedure.domain.AiDocument;
import com.sclera.applicationplane.procedure.domain.AiDocumentStatus;
import com.sclera.applicationplane.procedure.domain.ChatRole;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.AiDocumentResponse;
import com.sclera.applicationplane.procedure.mapper.AiMapper;
import com.sclera.applicationplane.procedure.repository.AiDocumentRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The topic-selection step before document-driven generation
 * (docs/procedures-v3.html DocsTab): one LLM pass proposes the inspectable
 * topics a document contains, the author picks, and generation is then scoped
 * to the picked topics. Topics are extracted once per document and cached on
 * the row, like the text — asking again returns the stored list.
 */
@Service
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class TopicExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TopicExtractionService.class);

    /**
     * Topics are a table-of-contents-level view, so one call over the leading
     * chunks is enough; generation still reads the whole document.
     */
    private static final int TOPIC_CHUNKS = 3;

    private final AiDocumentRepository documents;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final DocumentExtractionService extraction;
    private final AiProperties properties;
    private final AiMapper aiMapper;
    private final TaskExecutor executor;

    public TopicExtractionService(AiDocumentRepository documents,
                                  LlmClient llm,
                                  PromptLoader prompts,
                                  DocumentExtractionService extraction,
                                  AiProperties properties,
                                  AiMapper aiMapper,
                                  @Qualifier(AiConfig.EXECUTOR) TaskExecutor executor) {
        this.documents = documents;
        this.llm = llm;
        this.prompts = prompts;
        this.extraction = extraction;
        this.properties = properties;
        this.aiMapper = aiMapper;
        this.executor = executor;
    }

    /**
     * Starts topic extraction unless topics are already there (cached) or on the
     * way (EXTRACTING). The response carries topicsStatus; poll the document
     * until READY or FAILED.
     */
    public AiDocumentResponse extractTopics(UUID documentId) {
        UUID orgId = OrgContext.getOrgId();
        AiDocument document = documents.findByIdAndOrgId(documentId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        if (document.getTopicsStatus() == AiDocumentStatus.READY
                || document.getTopicsStatus() == AiDocumentStatus.EXTRACTING) {
            return aiMapper.toResponse(document);
        }
        if (document.getStatus() != AiDocumentStatus.READY) {
            throw new BusinessRuleException("Document '" + document.getFilename()
                    + "' has no extracted text yet; wait for it to become READY");
        }
        if (document.getExtractedText() == null || document.getExtractedText().isBlank()) {
            throw new BusinessRuleException("Document '" + document.getFilename()
                    + "' contains no extractable text");
        }

        document.setTopicsStatus(AiDocumentStatus.EXTRACTING);
        document.setTopicsError(null);
        documents.saveAndFlush(document);

        executor.execute(() -> extractInBackground(documentId, orgId));
        return aiMapper.toResponse(document);
    }

    private void extractInBackground(UUID documentId, UUID orgId) {
        OrgContext.clear();
        OrgContext.setOrgId(orgId);
        try {
            AiDocument document = documents.findByIdAndOrgId(documentId, orgId).orElseThrow();
            try {
                List<String> chunks = extraction.chunk(document.getExtractedText(),
                        properties.getChunkChars(), TOPIC_CHUNKS);
                String text = String.join("\n\n", chunks);

                String system = prompts.render("topic-extraction.system.md", Map.of());
                LlmClient.Result<LlmSchemas.TopicBatch> result = llm.complete(null, system,
                        List.of(new LlmClient.Turn(ChatRole.USER,
                                "Identify the inspectable topics in this document:\n\n--- DOCUMENT ---\n" + text)),
                        LlmSchemas.TopicBatch.class);

                document.setTopicsJson(aiMapper.writeTopics(result.value()));
                document.setTopicsStatus(AiDocumentStatus.READY);
                document.setTopicsError(null);
                log.info("Topic extraction for document {} done: {} groups (model={}, in={}, out={})",
                        documentId, result.value().groups().size(), result.model(),
                        result.inputTokens(), result.outputTokens());
            } catch (Exception e) {
                log.warn("Topic extraction failed for document {}: {}", documentId, e.getMessage());
                document.setTopicsStatus(AiDocumentStatus.FAILED);
                String error = e.getMessage() == null ? "Topic extraction failed" : e.getMessage();
                document.setTopicsError(error.length() <= 2000 ? error : error.substring(0, 2000));
            }
            documents.saveAndFlush(document);
        } finally {
            OrgContext.clear();
        }
    }
}
