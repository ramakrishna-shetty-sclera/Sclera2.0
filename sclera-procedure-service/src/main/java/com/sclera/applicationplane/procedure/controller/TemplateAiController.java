package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.domain.GeneratedQuestionStatus;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ChatSessionResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.GenerateFromDocumentRequest;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.GenerationJobResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ImportQuestionsRequest;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ImportQuestionsResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.QuestionDraftResponse;
import com.sclera.applicationplane.procedure.service.QuestionGenerationService;
import com.sclera.applicationplane.procedure.service.QuestionImportService;
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

import java.util.List;
import java.util.UUID;

/**
 * The template-scoped half of AI question generation: start a generation,
 * open a chat, review candidates, and the one shared import path into the
 * draft. Only exists when sclera.ai.enabled=true.
 */
@RestController
@RequestMapping("/api/v1/procedure-templates")
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class TemplateAiController {

    private final QuestionGenerationService generation;
    private final QuestionImportService imports;

    public TemplateAiController(QuestionGenerationService generation, QuestionImportService imports) {
        this.generation = generation;
        this.imports = imports;
    }

    /** Starts an async generation from an uploaded document; poll the returned job. */
    @PostMapping("/{id}/generate/document")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_edit')")
    public GenerationJobResponse generateFromDocument(@PathVariable UUID id,
                                                      @Valid @RequestBody GenerateFromDocumentRequest request) {
        return generation.generateFromDocument(id, request);
    }

    @PostMapping("/{id}/chat")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_edit')")
    public ChatSessionResponse createChatSession(@PathVariable UUID id) {
        return generation.createChatSession(id);
    }

    /** Candidates awaiting review (default), or by status. */
    @GetMapping("/{id}/question-drafts")
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_view')")
    public List<QuestionDraftResponse> listDrafts(@PathVariable UUID id,
                                                  @RequestParam(required = false) GeneratedQuestionStatus status) {
        return imports.listDrafts(id, status);
    }

    /**
     * THE shared import path: selected candidates enter the draft through the
     * same validate → mint keys → canonicalize pipeline as a hand edit.
     * Idempotent — already-imported candidates are reported, not re-added.
     */
    @PostMapping("/{id}/questions/import")
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_edit')")
    public ImportQuestionsResponse importQuestions(@PathVariable UUID id,
                                                   @Valid @RequestBody ImportQuestionsRequest request) {
        return imports.importQuestions(id, request.candidateIds());
    }

    @PostMapping("/{id}/question-drafts/{candidateId}/discard")
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_edit')")
    public QuestionDraftResponse discard(@PathVariable UUID id, @PathVariable UUID candidateId) {
        return imports.discard(id, candidateId);
    }
}
