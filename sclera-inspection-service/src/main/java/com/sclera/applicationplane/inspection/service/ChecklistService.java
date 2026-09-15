package com.sclera.applicationplane.inspection.service;

import com.sclera.applicationplane.inspection.domain.Checklist;
import com.sclera.applicationplane.inspection.domain.Checklist.Answer;
import com.sclera.applicationplane.inspection.domain.ChecklistStatus;
import com.sclera.applicationplane.inspection.domain.InspectionConfig;
import com.sclera.applicationplane.inspection.domain.InspectionTagging;
import com.sclera.applicationplane.inspection.domain.InspectionTagging.ProcedureTarget;
import com.sclera.applicationplane.inspection.domain.InspectionTagging.TaggedProcedure;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.AnswerInput;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.AnswerResponse;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.ChecklistResponse;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.GenerateRequest;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.HistoryResponse;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.SaveAnswersRequest;
import com.sclera.applicationplane.inspection.repository.ChecklistRepository;
import com.sclera.applicationplane.inspection.repository.InspectionConfigRepository;
import com.sclera.applicationplane.inspection.repository.InspectionTaggingRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ChecklistService {

    private final ChecklistRepository repository;
    private final InspectionConfigRepository configRepository;
    private final InspectionTaggingRepository taggingRepository;

    public ChecklistService(ChecklistRepository repository,
                            InspectionConfigRepository configRepository,
                            InspectionTaggingRepository taggingRepository) {
        this.repository = repository;
        this.configRepository = configRepository;
        this.taggingRepository = taggingRepository;
    }

    /** Generate one checklist per tagged procedure × target (or one per procedure with no targets). */
    public List<ChecklistResponse> generate(GenerateRequest request) {
        UUID orgId = OrgContext.getOrgId();
        InspectionConfig config = configRepository.findByIdAndOrgId(request.configId(), orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection configuration not found: " + request.configId()));
        InspectionTagging tagging = taggingRepository.getOrCreate(config.getId());
        if (tagging.getTaggedProcedures().isEmpty()) {
            throw new BusinessRuleException("Tag at least one procedure before generating checklists");
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<Checklist> created = new ArrayList<>();
        for (TaggedProcedure tp : tagging.getTaggedProcedures()) {
            if (tp.getTargets().isEmpty()) {
                created.add(build(config, tp, null, request.dueDate(), now));
            } else {
                for (ProcedureTarget target : tp.getTargets()) {
                    created.add(build(config, tp, target, request.dueDate(), now));
                }
            }
        }
        created.forEach(repository::save);
        return created.stream().map(this::toResponse).toList();
    }

    private Checklist build(InspectionConfig config, TaggedProcedure tp, ProcedureTarget target,
                            OffsetDateTime dueDate, OffsetDateTime now) {
        Checklist c = new Checklist();
        c.setId(UUID.randomUUID());
        c.setOrgId(config.getOrgId());
        c.setConfigId(config.getId());
        c.setConfigName(config.getName());
        c.setTaggedProcedureId(tp.getId());
        c.setProcedureId(tp.getProcedureId());
        c.setProcedureName(tp.getProcedureName());
        if (target != null) {
            c.setTargetType(target.getTargetType());
            c.setTargetId(target.getTargetId());
            c.setTargetName(target.getTargetName());
            c.setAssigneeEmail(target.getAssigneeEmail() != null ? target.getAssigneeEmail() : config.getAssigneeEmail());
        } else {
            c.setAssigneeEmail(config.getAssigneeEmail());
        }
        c.setStatus(ChecklistStatus.TODO);
        c.setDueDate(dueDate);
        c.setCheckInRequired(config.isEnableCheckInOut());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c.log("GENERATED", "Checklist created from inspection " + config.getName(), now);
        return c;
    }

    public List<ChecklistResponse> list(UUID configId, ChecklistStatus status) {
        return repository.findAllByOrgId(OrgContext.getOrgId(), configId, status).stream()
                .map(this::toResponse).toList();
    }

    public ChecklistResponse get(UUID id) {
        return toResponse(getOwned(id));
    }

    public ChecklistResponse checkIn(UUID id) {
        Checklist c = getOwned(id);
        if (!c.isCheckInRequired()) {
            throw new BusinessRuleException("Check-in is not enabled for this inspection");
        }
        if (c.getStatus() != ChecklistStatus.TODO) {
            throw new BusinessRuleException("Only a To-Do checklist can be checked in");
        }
        if (c.getCheckInAt() != null) {
            throw new BusinessRuleException("Already checked in");
        }
        OffsetDateTime now = OffsetDateTime.now();
        c.setCheckInAt(now);
        c.setUpdatedAt(now);
        c.log("CHECK_IN", null, now);
        return toResponse(repository.save(c));
    }

    public ChecklistResponse saveAnswers(UUID id, SaveAnswersRequest request) {
        Checklist c = getOwned(id);
        requireFillable(c);
        requireCheckedInIfNeeded(c);
        applyAnswers(c, request.answers());
        OffsetDateTime now = OffsetDateTime.now();
        if (request.checkout()) {
            c.setCheckOutAt(now);
            c.log("SAVE_AND_CHECKOUT", null, now);
        } else {
            c.log("SAVE", "Answers saved", now);
        }
        c.setUpdatedAt(now);
        return toResponse(repository.save(c));
    }

    /** Submit → COMPLETE, or FAILED if any answer is marked failed. */
    public ChecklistResponse submit(UUID id, SaveAnswersRequest request) {
        Checklist c = getOwned(id);
        requireFillable(c);
        requireCheckedInIfNeeded(c);
        if (request != null && request.answers() != null) {
            applyAnswers(c, request.answers());
        }
        boolean anyFailed = c.getAnswers().stream().anyMatch(Answer::isFailed);
        OffsetDateTime now = OffsetDateTime.now();
        c.setStatus(anyFailed ? ChecklistStatus.FAILED : ChecklistStatus.COMPLETE);
        if (c.getCheckOutAt() == null) c.setCheckOutAt(now);
        c.setUpdatedAt(now);
        c.log("SUBMIT", "Marked " + c.getStatus(), now);
        return toResponse(repository.save(c));
    }

    public ChecklistResponse addException(UUID id, String reason) {
        Checklist c = getOwned(id);
        if (c.getStatus() == ChecklistStatus.COMPLETE || c.getStatus() == ChecklistStatus.FAILED) {
            throw new BusinessRuleException("A finalized checklist cannot be moved to Exception");
        }
        OffsetDateTime now = OffsetDateTime.now();
        c.setStatus(ChecklistStatus.EXCEPTION);
        c.setExceptionReason(reason);
        c.setUpdatedAt(now);
        c.log("EXCEPTION", reason, now);
        return toResponse(repository.save(c));
    }

    /** Move INCOMPLETE / EXCEPTION back to TODO (logged in history). */
    public ChecklistResponse reopen(UUID id) {
        Checklist c = getOwned(id);
        if (c.getStatus() != ChecklistStatus.INCOMPLETE && c.getStatus() != ChecklistStatus.EXCEPTION) {
            throw new BusinessRuleException("Only Incomplete or Exception checklists can be reopened");
        }
        OffsetDateTime now = OffsetDateTime.now();
        c.setStatus(ChecklistStatus.TODO);
        c.setExceptionReason(null);
        c.setUpdatedAt(now);
        c.log("REOPEN", "Moved back to To-Do", now);
        return toResponse(repository.save(c));
    }

    /** Mark a To-Do checklist as Incomplete (the overdue transition; here triggered manually). */
    public ChecklistResponse markIncomplete(UUID id) {
        Checklist c = getOwned(id);
        if (c.getStatus() != ChecklistStatus.TODO) {
            throw new BusinessRuleException("Only a To-Do checklist can be marked Incomplete");
        }
        OffsetDateTime now = OffsetDateTime.now();
        c.setStatus(ChecklistStatus.INCOMPLETE);
        c.setUpdatedAt(now);
        c.log("INCOMPLETE", "Not filled by due date", now);
        return toResponse(repository.save(c));
    }

    public ChecklistResponse updateAssignee(UUID id, String assigneeEmail) {
        Checklist c = getOwned(id);
        OffsetDateTime now = OffsetDateTime.now();
        String previous = c.getAssigneeEmail();
        c.setAssigneeEmail(assigneeEmail);
        c.setUpdatedAt(now);
        c.log("ASSIGNEE", previous + " → " + assigneeEmail, now);
        return toResponse(repository.save(c));
    }

    public ChecklistResponse createWorkOrder(UUID id, String note) {
        Checklist c = getOwned(id);
        UUID workOrderId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        c.getWorkOrderIds().add(workOrderId);
        c.setUpdatedAt(now);
        c.log("WORK_ORDER", "Work order " + workOrderId + (note != null && !note.isBlank() ? ": " + note : ""), now);
        return toResponse(repository.save(c));
    }

    public void delete(UUID id) {
        repository.delete(getOwned(id));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Checklist getOwned(UUID id) {
        return repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Checklist not found: " + id));
    }

    private void requireFillable(Checklist c) {
        if (c.getStatus() == ChecklistStatus.COMPLETE || c.getStatus() == ChecklistStatus.FAILED) {
            throw new BusinessRuleException("Checklist is already finalized (" + c.getStatus() + ")");
        }
        if (c.getStatus() == ChecklistStatus.INCOMPLETE || c.getStatus() == ChecklistStatus.EXCEPTION) {
            throw new BusinessRuleException("Reopen the checklist to To-Do before filling");
        }
    }

    private void requireCheckedInIfNeeded(Checklist c) {
        if (c.isCheckInRequired() && c.getCheckInAt() == null) {
            throw new BusinessRuleException("Check in before filling this checklist");
        }
    }

    private void applyAnswers(Checklist c, List<AnswerInput> inputs) {
        if (inputs == null) return;
        c.getAnswers().clear();
        for (AnswerInput in : inputs) {
            Answer a = new Answer();
            a.setQuestionId(in.questionId());
            a.setValue(in.value());
            a.setFailed(in.failed());
            a.setComment(in.comment());
            c.getAnswers().add(a);
        }
    }

    private ChecklistResponse toResponse(Checklist c) {
        return new ChecklistResponse(
                c.getId(), c.getOrgId(), c.getConfigId(), c.getConfigName(),
                c.getTaggedProcedureId(), c.getProcedureId(), c.getProcedureName(),
                c.getTargetType(), c.getTargetId(), c.getTargetName(),
                c.getAssigneeEmail(), c.getStatus(), c.getDueDate(),
                c.isCheckInRequired(), c.getCheckInAt(), c.getCheckOutAt(), c.getExceptionReason(),
                c.getAnswers().stream().map(a ->
                        new AnswerResponse(a.getQuestionId(), a.getValue(), a.isFailed(), a.getComment())).toList(),
                c.getHistory().stream().map(h ->
                        new HistoryResponse(h.getAt(), h.getAction(), h.getDetail())).toList(),
                List.copyOf(c.getWorkOrderIds()),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
