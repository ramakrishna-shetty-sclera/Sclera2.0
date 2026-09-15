package com.sclera.applicationplane.inspection.service;

import com.sclera.applicationplane.inspection.domain.InspectionTagging;
import com.sclera.applicationplane.inspection.domain.InspectionTagging.OuterCondition;
import com.sclera.applicationplane.inspection.domain.InspectionTagging.ProcedureTarget;
import com.sclera.applicationplane.inspection.domain.InspectionTagging.TaggedProcedure;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.OuterConditionRequest;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.OuterConditionResponse;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.TagProcedureRequest;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.TaggedProcedureResponse;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.TaggingResponse;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.TargetRequest;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.TargetResponse;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.TargetUpdateRequest;
import com.sclera.applicationplane.inspection.repository.InspectionConfigRepository;
import com.sclera.applicationplane.inspection.repository.InspectionTaggingRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class InspectionTaggingService {

    private final InspectionTaggingRepository repository;
    private final InspectionConfigRepository configRepository;

    public InspectionTaggingService(InspectionTaggingRepository repository,
                                    InspectionConfigRepository configRepository) {
        this.repository = repository;
        this.configRepository = configRepository;
    }

    public TaggingResponse get(UUID configId) {
        return toResponse(taggingFor(configId));
    }

    public TaggedProcedureResponse tagProcedure(UUID configId, TagProcedureRequest request) {
        InspectionTagging tagging = taggingFor(configId);
        boolean already = tagging.getTaggedProcedures().stream()
                .anyMatch(tp -> tp.getProcedureId().equals(request.procedureId()));
        if (already) {
            throw new BusinessRuleException("Procedure already tagged to this inspection");
        }
        TaggedProcedure tp = new TaggedProcedure();
        tp.setId(UUID.randomUUID());
        tp.setProcedureId(request.procedureId());
        tp.setProcedureName(request.procedureName());
        tagging.getTaggedProcedures().add(tp);
        return toResponse(tp);
    }

    public void untagProcedure(UUID configId, UUID taggedProcedureId) {
        InspectionTagging tagging = taggingFor(configId);
        boolean removed = tagging.getTaggedProcedures().removeIf(tp -> tp.getId().equals(taggedProcedureId));
        if (!removed) {
            throw new ResourceNotFoundException("Tagged procedure not found: " + taggedProcedureId);
        }
    }

    public TargetResponse addTarget(UUID configId, UUID taggedProcedureId, TargetRequest request) {
        TaggedProcedure tp = procedure(configId, taggedProcedureId);
        boolean dup = tp.getTargets().stream().anyMatch(t ->
                t.getTargetType() == request.targetType() && t.getTargetId().equals(request.targetId()));
        if (dup) {
            throw new BusinessRuleException("That " + request.targetType().name().toLowerCase()
                    + " is already tagged to this procedure");
        }
        ProcedureTarget target = new ProcedureTarget();
        target.setId(UUID.randomUUID());
        target.setTargetType(request.targetType());
        target.setTargetId(request.targetId());
        target.setTargetName(request.targetName());
        target.setCondition(trimToNull(request.condition()));
        target.setAssigneeEmail(trimToNull(request.assigneeEmail()));
        tp.getTargets().add(target);
        return toResponse(target);
    }

    public TargetResponse updateTarget(UUID configId, UUID taggedProcedureId, UUID targetId,
                                       TargetUpdateRequest request) {
        ProcedureTarget target = procedure(configId, taggedProcedureId).getTargets().stream()
                .filter(t -> t.getId().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Target not found: " + targetId));
        target.setCondition(trimToNull(request.condition()));
        target.setAssigneeEmail(trimToNull(request.assigneeEmail()));
        return toResponse(target);
    }

    public void removeTarget(UUID configId, UUID taggedProcedureId, UUID targetId) {
        boolean removed = procedure(configId, taggedProcedureId).getTargets()
                .removeIf(t -> t.getId().equals(targetId));
        if (!removed) {
            throw new ResourceNotFoundException("Target not found: " + targetId);
        }
    }

    public OuterConditionResponse addCondition(UUID configId, OuterConditionRequest request) {
        InspectionTagging tagging = taggingFor(configId);
        OuterCondition c = new OuterCondition();
        c.setId(UUID.randomUUID());
        c.setDescription(request.description().trim());
        c.setEmailAlert(request.emailAlert());
        c.setCreateWorkOrder(request.createWorkOrder());
        tagging.getOuterConditions().add(c);
        return toResponse(c);
    }

    public void removeCondition(UUID configId, UUID conditionId) {
        InspectionTagging tagging = taggingFor(configId);
        boolean removed = tagging.getOuterConditions().removeIf(c -> c.getId().equals(conditionId));
        if (!removed) {
            throw new ResourceNotFoundException("Condition not found: " + conditionId);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Verifies the config belongs to the caller's org, then returns its tagging aggregate. */
    private InspectionTagging taggingFor(UUID configId) {
        configRepository.findByIdAndOrgId(configId, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Inspection configuration not found: " + configId));
        return repository.getOrCreate(configId);
    }

    private TaggedProcedure procedure(UUID configId, UUID taggedProcedureId) {
        return taggingFor(configId).getTaggedProcedures().stream()
                .filter(tp -> tp.getId().equals(taggedProcedureId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Tagged procedure not found: " + taggedProcedureId));
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private TaggingResponse toResponse(InspectionTagging t) {
        return new TaggingResponse(
                t.getConfigId(),
                t.getTaggedProcedures().stream().map(this::toResponse).toList(),
                t.getOuterConditions().stream().map(this::toResponse).toList());
    }

    private TaggedProcedureResponse toResponse(TaggedProcedure tp) {
        return new TaggedProcedureResponse(
                tp.getId(), tp.getProcedureId(), tp.getProcedureName(),
                tp.getTargets().stream().map(this::toResponse).toList());
    }

    private TargetResponse toResponse(ProcedureTarget t) {
        return new TargetResponse(t.getId(), t.getTargetType(), t.getTargetId(),
                t.getTargetName(), t.getCondition(), t.getAssigneeEmail());
    }

    private OuterConditionResponse toResponse(OuterCondition c) {
        return new OuterConditionResponse(c.getId(), c.getDescription(), c.isEmailAlert(), c.isCreateWorkOrder());
    }
}
