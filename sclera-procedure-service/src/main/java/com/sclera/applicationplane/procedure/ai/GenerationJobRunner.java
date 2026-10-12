package com.sclera.applicationplane.procedure.ai;

import com.sclera.applicationplane.procedure.domain.AiDocument;
import com.sclera.applicationplane.procedure.domain.ChatRole;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestion;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestionSource;
import com.sclera.applicationplane.procedure.domain.GenerationJob;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.ResultType;
import com.sclera.applicationplane.procedure.mapper.AiMapper;
import com.sclera.applicationplane.procedure.repository.AiDocumentRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.ResultTypeRepository;
import com.sclera.controlplane.common.security.OrgContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The background half of document-driven generation. Runs on the ai executor
 * with no request context, so the org and user travel with the work and
 * OrgContext is re-established here — that is also what points Hibernate at
 * the right tenant schema.
 *
 * LLM calls happen outside any transaction (a connection is not held through
 * a long API call); state lands via {@link GenerationJobStateService} in small
 * transactions: RUNNING, then candidates + COMPLETED together, or FAILED.
 */
@Component
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class GenerationJobRunner {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobRunner.class);

    private final GenerationJobStateService state;
    private final AiDocumentRepository documents;
    private final ProcedureTemplateRepository templates;
    private final ResultTypeRepository resultTypes;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final CandidateMapper candidateMapper;
    private final DedupService dedup;
    private final DocumentExtractionService extraction;
    private final AiProperties properties;
    private final AiMapper aiMapper;

    public GenerationJobRunner(GenerationJobStateService state,
                               AiDocumentRepository documents,
                               ProcedureTemplateRepository templates,
                               ResultTypeRepository resultTypes,
                               LlmClient llm,
                               PromptLoader prompts,
                               CandidateMapper candidateMapper,
                               DedupService dedup,
                               DocumentExtractionService extraction,
                               AiProperties properties,
                               AiMapper aiMapper) {
        this.state = state;
        this.documents = documents;
        this.templates = templates;
        this.resultTypes = resultTypes;
        this.llm = llm;
        this.prompts = prompts;
        this.candidateMapper = candidateMapper;
        this.dedup = dedup;
        this.extraction = extraction;
        this.properties = properties;
        this.aiMapper = aiMapper;
    }

    /** Entry point for the executor. Owns context setup/teardown and failure capture. */
    public void run(UUID jobId, UUID orgId, UUID userId, String instructions) {
        OrgContext.clear();
        OrgContext.setOrgId(orgId);
        OrgContext.setUserId(userId);
        try {
            generate(jobId, orgId, instructions);
        } catch (Exception e) {
            log.error("Generation job {} failed: {}", jobId, e.getMessage(), e);
            state.markFailed(jobId, e.getMessage());
        } finally {
            OrgContext.clear();
        }
    }

    private void generate(UUID jobId, UUID orgId, String instructions) {
        GenerationJob job = state.markRunning(jobId, orgId);
        AiDocument document = documents.findByIdAndOrgId(job.getDocumentId(), orgId).orElseThrow();
        ProcedureTemplate template = templates.findByIdAndOrgId(job.getTemplateId(), orgId).orElseThrow();

        Map<String, String> activeResults = activeResults(orgId);
        String system = prompts.render("question-generation.system.md",
                Map.of("resultTypes", renderResultTypes(activeResults)));

        List<String> chunks = extraction.chunk(document.getExtractedText(),
                properties.getChunkChars(), properties.getMaxChunks());
        if (extraction.truncates(document.getExtractedText(), properties.getChunkChars(), properties.getMaxChunks())) {
            log.warn("Generation job {}: document {} exceeds {} chunks of {} chars; the tail was not processed",
                    jobId, document.getId(), properties.getMaxChunks(), properties.getChunkChars());
        }

        DedupService.Index index = dedup.buildIndex(orgId);
        List<GeneratedQuestion> produced = new ArrayList<>();
        long inputTokens = 0;
        long outputTokens = 0;
        String modelUsed = null;
        // A question generated from chunk 1 must not come back from chunk 3.
        Set<String> seenThisRun = new HashSet<>();

        for (int chunkNo = 0; chunkNo < chunks.size(); chunkNo++) {
            String user = prompts.render("document-generation.user.md", Map.of(
                    "templateName", template.getName(),
                    "instructions", instructions == null || instructions.isBlank() ? "(none)" : instructions,
                    "chunkNo", String.valueOf(chunkNo + 1),
                    "chunkCount", String.valueOf(chunks.size()),
                    "documentText", chunks.get(chunkNo)));

            LlmClient.Result<LlmSchemas.GeneratedBatch> result = llm.complete(job.getModel(), system,
                    List.of(new LlmClient.Turn(ChatRole.USER, user)),
                    LlmSchemas.GeneratedBatch.class);
            inputTokens += result.inputTokens();
            outputTokens += result.outputTokens();
            modelUsed = result.model();

            CandidateMapper.SanitizeOutcome outcome =
                    candidateMapper.sanitizeAll(result.value().questions(), activeResults.keySet());
            for (CandidateMapper.Accepted accepted : outcome.accepted()) {
                if (!seenThisRun.add(DedupService.normalize(accepted.question().text()))) {
                    continue;
                }
                produced.add(toCandidate(accepted, job, template.getId(), orgId, index));
            }
        }

        state.complete(jobId, orgId, produced, modelUsed, inputTokens, outputTokens);
        log.info("Generation job {} completed: {} candidates from {} chunks (model={}, in={}, out={})",
                jobId, produced.size(), chunks.size(), modelUsed, inputTokens, outputTokens);
    }

    private GeneratedQuestion toCandidate(CandidateMapper.Accepted accepted, GenerationJob job,
                                          UUID templateId, UUID orgId, DedupService.Index index) {
        GeneratedQuestion candidate = new GeneratedQuestion();
        candidate.setOrgId(orgId);
        candidate.setTemplateId(templateId);
        candidate.setSource(GeneratedQuestionSource.DOCUMENT);
        candidate.setSourceRef(job.getId());
        candidate.setSourceSpan(clip(accepted.raw().sourceSpan(), 500));
        candidate.setConfidence(accepted.raw().confidence());
        candidate.setQuestionJson(aiMapper.writeQuestion(accepted.question()));
        candidate.setSimilarExisting(aiMapper.writeSimilar(
                dedup.findSimilar(index, accepted.question().text()).orElse(null)));
        return candidate;
    }

    private Map<String, String> activeResults(UUID orgId) {
        return resultTypes.findAllByOrgIdAndActiveOrderBySeverityOrderAsc(orgId, true).stream()
                .collect(Collectors.toMap(ResultType::getKey, ResultType::getName,
                        (a, b) -> a, LinkedHashMap::new));
    }

    public static String renderResultTypes(Map<String, String> activeResults) {
        if (activeResults.isEmpty()) {
            return "(none defined — leave every result null)";
        }
        return activeResults.entrySet().stream()
                .map(e -> "- `" + e.getKey() + "` — " + e.getValue())
                .collect(Collectors.joining("\n"));
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
