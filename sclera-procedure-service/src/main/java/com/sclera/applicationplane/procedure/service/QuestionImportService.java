package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.ai.CandidateMapper;
import com.sclera.applicationplane.procedure.ai.CandidateQuestion;
import com.sclera.applicationplane.procedure.definition.DefinitionCanonicalizer;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.KeyMinter;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestion;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestionSource;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestionStatus;
import com.sclera.applicationplane.procedure.domain.ItemSource;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ImportQuestionsResponse;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.ImportedQuestion;
import com.sclera.applicationplane.procedure.dto.AiGenerationDtos.QuestionDraftResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.mapper.AiMapper;
import com.sclera.applicationplane.procedure.repository.GeneratedQuestionRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateRepository;
import com.sclera.applicationplane.procedure.repository.ProcedureTemplateVersionRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The single path by which AI-generated candidates enter a procedure — both
 * features end here, and it is a thin wrapper around the same draft-save
 * pipeline a hand edit uses ({@link ProcedureTemplateService#saveDraft}):
 * structure validation, key minting from the template's counter,
 * canonicalization, optimistic lock. Nothing AI-specific is persisted into
 * the definition except {@code source} on each imported item.
 *
 * Idempotent: an IMPORTED candidate is skipped (its earlier key is returned),
 * so retrying a request that timed out cannot add the same question twice.
 *
 * Follow-up wrinkle: a follow-up's {@code when} must name its parent option's
 * key, and new options have no key until minted. For candidates with
 * follow-ups the option keys are minted here, under the same template lock the
 * save pipeline uses — the pipeline then accepts them as already-issued keys.
 */
@Service
@Transactional
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class QuestionImportService {

    private final ProcedureTemplateRepository templates;
    private final ProcedureTemplateVersionRepository versions;
    private final GeneratedQuestionRepository candidates;
    private final ProcedureTemplateService templateService;
    private final DefinitionCanonicalizer canonicalizer;
    private final CandidateMapper candidateMapper;
    private final AiMapper aiMapper;

    public QuestionImportService(ProcedureTemplateRepository templates,
                                 ProcedureTemplateVersionRepository versions,
                                 GeneratedQuestionRepository candidates,
                                 ProcedureTemplateService templateService,
                                 DefinitionCanonicalizer canonicalizer,
                                 CandidateMapper candidateMapper,
                                 AiMapper aiMapper) {
        this.templates = templates;
        this.versions = versions;
        this.candidates = candidates;
        this.templateService = templateService;
        this.canonicalizer = canonicalizer;
        this.candidateMapper = candidateMapper;
        this.aiMapper = aiMapper;
    }

    public ImportQuestionsResponse importQuestions(UUID templateId, List<UUID> candidateIds) {
        UUID orgId = OrgContext.getOrgId();
        ProcedureTemplate template = templates.lockByIdAndOrgId(templateId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Procedure template not found: " + templateId));
        if (template.getStatus() == TemplateStatus.ARCHIVED) {
            throw new BusinessRuleException("Procedure '" + template.getName()
                    + "' is archived and cannot be changed");
        }
        ProcedureTemplateVersion draft = versions.findByTemplateIdAndState(template.getId(), VersionState.DRAFT)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Procedure template " + templateId + " has no draft; create one from a version"));

        Map<UUID, GeneratedQuestion> found = candidates.findAllByIdInAndOrgId(candidateIds, orgId).stream()
                .collect(Collectors.toMap(GeneratedQuestion::getId, c -> c));
        List<GeneratedQuestion> toImport = new ArrayList<>();
        List<UUID> alreadyImported = new ArrayList<>();
        for (UUID candidateId : candidateIds) {
            GeneratedQuestion candidate = found.get(candidateId);
            if (candidate == null || !candidate.getTemplateId().equals(templateId)) {
                throw new ResourceNotFoundException(
                        "Candidate " + candidateId + " does not exist for this procedure");
            }
            switch (candidate.getStatus()) {
                case IMPORTED -> alreadyImported.add(candidateId);
                case DISCARDED -> throw new BusinessRuleException("Candidate " + candidateId
                        + " was discarded; it must be restored before it can be imported");
                case DRAFT -> toImport.add(candidate);
            }
        }

        if (toImport.isEmpty()) {
            // Pure replay of an earlier import: change nothing, report the keys
            // that import minted.
            return new ImportQuestionsResponse(List.of(),
                    alreadyImported,
                    templateService.getDraft(templateId));
        }

        DefinitionDocument current = canonicalizer.parse(draft.getDefinitionJson());
        List<Item> merged = new ArrayList<>(current.items());
        for (GeneratedQuestion candidate : toImport) {
            CandidateQuestion question = aiMapper.readQuestion(candidate.getQuestionJson());
            merged.add(candidateMapper.toItem(question, itemSource(candidate.getSource()),
                    () -> KeyMinter.OPTION_PREFIX + template.nextKeyNumber()));
        }

        VersionResponse saved = templateService.saveDraft(templateId, new SaveDraftRequest(
                new DefinitionDocument(current.schema(), merged),
                draft.getRowVersion(),
                "Imported " + toImport.size() + " AI-generated question"
                        + (toImport.size() == 1 ? "" : "s")));

        // The appended items are the tail of the saved document, in request
        // order, now carrying their minted keys.
        List<Item> savedItems = saved.definition().items();
        List<ImportedQuestion> imported = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 0; i < toImport.size(); i++) {
            GeneratedQuestion candidate = toImport.get(i);
            String key = savedItems.get(current.items().size() + i).key();
            candidate.markImported(key, now);
            imported.add(new ImportedQuestion(candidate.getId(), key));
        }
        candidates.saveAllAndFlush(toImport);

        return new ImportQuestionsResponse(imported, alreadyImported, saved);
    }

    @Transactional(readOnly = true)
    public List<QuestionDraftResponse> listDrafts(UUID templateId, GeneratedQuestionStatus status) {
        UUID orgId = OrgContext.getOrgId();
        templates.findByIdAndOrgId(templateId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Procedure template not found: " + templateId));
        List<GeneratedQuestion> rows = status == null
                ? candidates.findAllByTemplateIdAndOrgIdOrderByCreatedAtAsc(templateId, orgId)
                : candidates.findAllByTemplateIdAndOrgIdAndStatusOrderByCreatedAtAsc(templateId, orgId, status);
        return rows.stream().map(aiMapper::toResponse).toList();
    }

    /** Review action: a discarded candidate leaves the list but keeps its audit row. */
    public QuestionDraftResponse discard(UUID templateId, UUID candidateId) {
        UUID orgId = OrgContext.getOrgId();
        GeneratedQuestion candidate = candidates.findAllByIdInAndOrgId(List.of(candidateId), orgId).stream()
                .filter(c -> c.getTemplateId().equals(templateId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate " + candidateId + " does not exist for this procedure"));
        if (candidate.getStatus() == GeneratedQuestionStatus.IMPORTED) {
            throw new BusinessRuleException(
                    "Candidate " + candidateId + " was already imported; remove it from the draft instead");
        }
        candidate.setStatus(GeneratedQuestionStatus.DISCARDED);
        candidates.saveAndFlush(candidate);
        return aiMapper.toResponse(candidate);
    }

    private static ItemSource itemSource(GeneratedQuestionSource source) {
        return source == GeneratedQuestionSource.DOCUMENT ? ItemSource.DOCUMENT : ItemSource.SUGGESTED;
    }
}
