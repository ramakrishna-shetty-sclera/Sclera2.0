package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.ai.AiConfig;
import com.sclera.applicationplane.procedure.ai.CandidateMapper;
import com.sclera.applicationplane.procedure.ai.DedupService;
import com.sclera.applicationplane.procedure.ai.DocumentExtractionService;
import com.sclera.applicationplane.procedure.ai.GenerationJobRunner;
import com.sclera.applicationplane.procedure.ai.LlmClient;
import com.sclera.applicationplane.procedure.ai.LlmSchemas;
import com.sclera.applicationplane.procedure.ai.PromptLoader;
import com.sclera.applicationplane.procedure.domain.AiDocument;
import com.sclera.applicationplane.procedure.domain.AiDocumentStatus;
import com.sclera.applicationplane.procedure.domain.ChatMessage;
import com.sclera.applicationplane.procedure.domain.ChatRole;
import com.sclera.applicationplane.procedure.domain.ChatSession;
import com.sclera.applicationplane.procedure.domain.ChatSessionStatus;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestion;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestionSource;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestionStatus;
import com.sclera.applicationplane.procedure.domain.GenerationJob;
import com.sclera.applicationplane.procedure.domain.GenerationJobStatus;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.ResultType;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.AiDocumentResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ChatMessageRequest;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ChatSessionResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ChatTurnResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.GenerateFromDocumentRequest;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.GenerationJobResponse;
import com.sclera.applicationplane.procedure.mapper.AiMapper;
import com.sclera.applicationplane.procedure.repository.AiDocumentRepository;
import com.sclera.applicationplane.procedure.repository.ChatMessageRepository;
import com.sclera.applicationplane.procedure.repository.ChatSessionRepository;
import com.sclera.applicationplane.procedure.repository.GeneratedQuestionRepository;
import com.sclera.applicationplane.procedure.repository.GenerationJobRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateVersionRepository;
import com.sclera.applicationplane.procedure.repository.ResultTypeRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI-assisted question generation: one service, two entry points, one output.
 * {@link #generateFromDocument} (async, via a {@code generation_job}) and
 * {@link #generateFromChat} (synchronous turn) both end in rows of
 * {@code generated_question} — the GeneratedQuestionSet an author reviews and
 * imports through {@link QuestionImportService}, never directly into a draft.
 *
 * Transactions are deliberately per-repository-call here: LLM requests take
 * tens of seconds and must not hold a connection, and every state written is
 * safe on its own (a candidate row without its assistant reply is reviewable;
 * the reverse is answerable).
 */
@Service
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class QuestionGenerationService {

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationService.class);

    private final AiDocumentRepository documents;
    private final GenerationJobRepository jobs;
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final GeneratedQuestionRepository candidates;
    private final ProcedureTemplateRepository templates;
    private final ProcedureTemplateVersionRepository versions;
    private final ResultTypeRepository resultTypes;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final CandidateMapper candidateMapper;
    private final DedupService dedup;
    private final DocumentExtractionService extraction;
    private final GenerationJobRunner runner;
    private final AiMapper aiMapper;
    private final TaskExecutor executor;

    public QuestionGenerationService(AiDocumentRepository documents,
                                     GenerationJobRepository jobs,
                                     ChatSessionRepository sessions,
                                     ChatMessageRepository messages,
                                     GeneratedQuestionRepository candidates,
                                     ProcedureTemplateRepository templates,
                                     ProcedureTemplateVersionRepository versions,
                                     ResultTypeRepository resultTypes,
                                     LlmClient llm,
                                     PromptLoader prompts,
                                     CandidateMapper candidateMapper,
                                     DedupService dedup,
                                     DocumentExtractionService extraction,
                                     GenerationJobRunner runner,
                                     AiMapper aiMapper,
                                     @Qualifier(AiConfig.EXECUTOR) TaskExecutor executor) {
        this.documents = documents;
        this.jobs = jobs;
        this.sessions = sessions;
        this.messages = messages;
        this.candidates = candidates;
        this.templates = templates;
        this.versions = versions;
        this.resultTypes = resultTypes;
        this.llm = llm;
        this.prompts = prompts;
        this.candidateMapper = candidateMapper;
        this.dedup = dedup;
        this.extraction = extraction;
        this.runner = runner;
        this.aiMapper = aiMapper;
        this.executor = executor;
    }

    // --- documents ----------------------------------------------------------

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

    public AiDocumentResponse getDocument(UUID id) {
        return aiMapper.toResponse(ownedDocument(id));
    }

    // --- Feature 1: document → questions ------------------------------------

    public GenerationJobResponse generateFromDocument(UUID templateId, GenerateFromDocumentRequest request) {
        ProcedureTemplate template = ownedActiveTemplate(templateId);
        AiDocument document = ownedDocument(request.documentId());
        switch (document.getStatus()) {
            case PENDING, EXTRACTING -> throw new BusinessRuleException(
                    "Document '" + document.getFilename() + "' is still being processed; try again shortly");
            case FAILED -> throw new BusinessRuleException(
                    "Document '" + document.getFilename() + "' could not be read: " + document.getError());
            case READY -> { }
        }
        if (document.getExtractedText() == null || document.getExtractedText().isBlank()) {
            throw new BusinessRuleException(
                    "Document '" + document.getFilename() + "' contains no extractable text");
        }

        GenerationJob job = new GenerationJob();
        UUID orgId = OrgContext.getOrgId();
        UUID userId = OrgContext.getUserId();
        job.setOrgId(orgId);
        job.setTemplateId(template.getId());
        job.setDocumentId(document.getId());
        // Resolved now so a bad override fails this request, not the job.
        job.setModel(llm.resolveModel(request.model()));
        job.setCreatedBy(userId);
        jobs.saveAndFlush(job);

        UUID jobId = job.getId();
        executor.execute(() -> runner.run(jobId, orgId, userId, request.instructions()));
        return aiMapper.toResponse(job, List.of());
    }

    public GenerationJobResponse getJob(UUID jobId) {
        GenerationJob job = jobs.findByIdAndOrgId(jobId, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
        List<GeneratedQuestion> produced = job.getStatus() == GenerationJobStatus.COMPLETED
                ? candidates.findAllBySourceRefOrderByCreatedAtAsc(job.getId())
                : List.of();
        return aiMapper.toResponse(job, produced);
    }

    // --- Feature 2: chat → questions ----------------------------------------

    public ChatSessionResponse createChatSession(UUID templateId) {
        ProcedureTemplate template = ownedActiveTemplate(templateId);
        ChatSession session = new ChatSession();
        session.setOrgId(OrgContext.getOrgId());
        session.setTemplateId(template.getId());
        session.setCreatedBy(OrgContext.getUserId());
        sessions.saveAndFlush(session);
        return aiMapper.toResponse(session);
    }

    public ChatTurnResponse generateFromChat(UUID sessionId, ChatMessageRequest request) {
        UUID orgId = OrgContext.getOrgId();
        ChatSession session = sessions.findByIdAndOrgId(sessionId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found: " + sessionId));
        if (session.getStatus() != ChatSessionStatus.ACTIVE) {
            throw new BusinessRuleException("This chat session is closed");
        }
        ProcedureTemplate template = ownedActiveTemplate(session.getTemplateId());

        // Snapshot the numbered candidate list BEFORE the call — the model's
        // revisions/removals refer to these numbers.
        List<GeneratedQuestion> numbered =
                candidates.findAllBySourceRefAndStatusOrderByCreatedAtAsc(sessionId, GeneratedQuestionStatus.DRAFT);

        ChatMessage userMessage = new ChatMessage();
        userMessage.setOrgId(orgId);
        userMessage.setSessionId(sessionId);
        userMessage.setRole(ChatRole.USER);
        userMessage.setContent(request.message().strip());
        messages.saveAndFlush(userMessage);

        List<ChatMessage> history = messages.findAllBySessionIdOrderByCreatedAtAsc(sessionId);
        int turnNo = (int) history.stream().filter(m -> m.getRole() == ChatRole.USER).count();

        Map<String, String> activeResults = activeResults(orgId);
        String draftJson = versions.findByTemplateIdAndState(template.getId(), VersionState.DRAFT)
                .map(v -> v.getDefinitionJson())
                .orElse("(the procedure has no draft yet)");
        String system = prompts.render("question-generation.system.md",
                Map.of("resultTypes", GenerationJobRunner.renderResultTypes(activeResults)))
                + "\n\n"
                + prompts.render("chat.system.md", Map.of(
                        "templateName", template.getName(),
                        "draftDefinition", draftJson,
                        "candidates", renderCandidates(numbered)));

        List<LlmClient.Turn> turns = history.stream()
                .map(m -> new LlmClient.Turn(m.getRole(), m.getContent()))
                .toList();
        LlmClient.Result<LlmSchemas.ChatTurn> result =
                llm.complete(request.model(), system, turns, LlmSchemas.ChatTurn.class);
        LlmSchemas.ChatTurn turn = result.value();

        applyChatTurn(turn, session, template.getId(), orgId, numbered, turnNo, activeResults);

        ChatMessage reply = new ChatMessage();
        reply.setOrgId(orgId);
        reply.setSessionId(sessionId);
        reply.setRole(ChatRole.ASSISTANT);
        reply.setContent(turn.reply() == null || turn.reply().isBlank() ? "(updated the candidates)" : turn.reply());
        messages.saveAndFlush(reply);

        List<GeneratedQuestion> current =
                candidates.findAllBySourceRefAndStatusOrderByCreatedAtAsc(sessionId, GeneratedQuestionStatus.DRAFT);
        return new ChatTurnResponse(sessionId, reply.getContent(),
                current.stream().map(aiMapper::toResponse).toList());
    }

    private void applyChatTurn(LlmSchemas.ChatTurn turn, ChatSession session, UUID templateId, UUID orgId,
                               List<GeneratedQuestion> numbered, int turnNo, Map<String, String> activeResults) {
        DedupService.Index index = dedup.buildIndex(orgId);

        CandidateMapper.SanitizeOutcome outcome =
                candidateMapper.sanitizeAll(turn.newQuestions(), activeResults.keySet());
        for (CandidateMapper.Accepted accepted : outcome.accepted()) {
            GeneratedQuestion candidate = new GeneratedQuestion();
            candidate.setOrgId(orgId);
            candidate.setTemplateId(templateId);
            candidate.setSource(GeneratedQuestionSource.CHAT);
            candidate.setSourceRef(session.getId());
            candidate.setTurnNo(turnNo);
            candidate.setSourceSpan(clip(accepted.raw().sourceSpan(), 500));
            candidate.setConfidence(accepted.raw().confidence());
            candidate.setQuestionJson(aiMapper.writeQuestion(accepted.question()));
            candidate.setSimilarExisting(aiMapper.writeSimilar(
                    dedup.findSimilar(index, accepted.question().text()).orElse(null)));
            candidates.save(candidate);
        }

        for (LlmSchemas.ChatRevision revision : turn.revisions()) {
            GeneratedQuestion target = byNumber(numbered, revision.candidateNumber());
            if (target == null || revision.question() == null) {
                log.warn("Chat session {}: revision names candidate {} which does not exist",
                        session.getId(), revision.candidateNumber());
                continue;
            }
            try {
                var sanitized = candidateMapper.sanitize(revision.question().toCandidate(), activeResults.keySet());
                target.setQuestionJson(aiMapper.writeQuestion(sanitized));
                target.setConfidence(revision.question().confidence());
                target.setTurnNo(turnNo);
                target.setSimilarExisting(aiMapper.writeSimilar(
                        dedup.findSimilar(index, sanitized.text()).orElse(null)));
                candidates.save(target);
            } catch (ValidationException e) {
                log.warn("Chat session {}: revision of candidate {} rejected: {}",
                        session.getId(), revision.candidateNumber(), e.getMessage());
            }
        }

        for (Integer number : turn.removeCandidateNumbers()) {
            GeneratedQuestion target = byNumber(numbered, number == null ? -1 : number);
            if (target != null) {
                target.setStatus(GeneratedQuestionStatus.DISCARDED);
                candidates.save(target);
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    private static GeneratedQuestion byNumber(List<GeneratedQuestion> numbered, int oneBased) {
        return oneBased >= 1 && oneBased <= numbered.size() ? numbered.get(oneBased - 1) : null;
    }

    private String renderCandidates(List<GeneratedQuestion> numbered) {
        if (numbered.isEmpty()) {
            return "(none yet)";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < numbered.size(); i++) {
            out.append(i + 1).append(". ").append(numbered.get(i).getQuestionJson()).append('\n');
        }
        return out.toString();
    }

    private Map<String, String> activeResults(UUID orgId) {
        return resultTypes.findAllByOrgIdAndActiveOrderBySeverityOrderAsc(orgId, true).stream()
                .collect(Collectors.toMap(ResultType::getKey, ResultType::getName,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private ProcedureTemplate ownedActiveTemplate(UUID templateId) {
        ProcedureTemplate template = templates.findByIdAndOrgId(templateId, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Procedure template not found: " + templateId));
        if (template.getStatus() == TemplateStatus.ARCHIVED) {
            throw new BusinessRuleException("Procedure '" + template.getName()
                    + "' is archived and cannot be changed");
        }
        return template;
    }

    private AiDocument ownedDocument(UUID id) {
        return documents.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + id));
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
