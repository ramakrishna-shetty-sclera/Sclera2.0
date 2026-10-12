package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.AiDocumentResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ChatMessageRequest;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ChatTurnResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.GenerationJobResponse;
import com.sclera.applicationplane.procedure.service.AiDocumentService;
import com.sclera.applicationplane.procedure.service.QuestionGenerationService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * AI question generation: the resources that are not template-scoped —
 * uploaded documents, generation jobs, chat messages. Template-scoped
 * operations live in {@link TemplateAiController}. Only exists when
 * sclera.ai.enabled=true (AI_QUESTION_GENERATION_ENABLED).
 *
 * NOTE for the gateway: /api/v1/ai/** must be in the procedure-service route
 * predicate (see README, "API gateway integration").
 */
@RestController
@RequestMapping("/api/v1/ai")
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class AiController {

    private final QuestionGenerationService service;
    private final AiDocumentService documents;

    public AiController(QuestionGenerationService service, AiDocumentService documents) {
        this.service = service;
        this.documents = documents;
    }

    /** Upload a source document (PDF/DOCX/TXT). Text extraction runs in the background. */
    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public AiDocumentResponse upload(@RequestParam("file") MultipartFile file) {
        return documents.upload(file);
    }

    /** The organization's document library, newest first — for reuse and re-generation. */
    @GetMapping("/documents")
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public List<AiDocumentResponse> listDocuments() {
        return documents.list();
    }

    @GetMapping("/documents/{id}")
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public AiDocumentResponse getDocument(@PathVariable UUID id) {
        return documents.getDocument(id);
    }

    /** Status + the GeneratedQuestionSet once COMPLETED. */
    @GetMapping("/generation-jobs/{jobId}")
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public GenerationJobResponse getJob(@PathVariable UUID jobId) {
        return service.getJob(jobId);
    }

    /** One chat turn: the author's message in, the reply + updated candidates out. */
    @PostMapping("/chat/{sessionId}/messages")
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public ChatTurnResponse sendMessage(@PathVariable UUID sessionId,
                                        @Valid @RequestBody ChatMessageRequest request) {
        return service.generateFromChat(sessionId, request);
    }
}
