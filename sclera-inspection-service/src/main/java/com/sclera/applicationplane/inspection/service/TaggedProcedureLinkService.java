package com.sclera.applicationplane.inspection.service;

import com.sclera.applicationplane.inspection.domain.Checklist;
import com.sclera.applicationplane.inspection.domain.ChecklistSource;
import com.sclera.applicationplane.inspection.domain.ChecklistStatus;
import com.sclera.applicationplane.inspection.domain.TaggedProcedureLink;
import com.sclera.applicationplane.inspection.domain.TargetType;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.ChecklistResponse;
import com.sclera.applicationplane.inspection.dto.TaggedProcedureLinkDtos.CreateLinkRequest;
import com.sclera.applicationplane.inspection.dto.TaggedProcedureLinkDtos.FillRequest;
import com.sclera.applicationplane.inspection.dto.TaggedProcedureLinkDtos.OneTimeRequest;
import com.sclera.applicationplane.inspection.dto.TaggedProcedureLinkDtos.TaggedProcedureLinkResponse;
import com.sclera.applicationplane.inspection.repository.ChecklistRepository;
import com.sclera.applicationplane.inspection.repository.TaggedProcedureLinkRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TaggedProcedureLinkService {

    private final TaggedProcedureLinkRepository repository;
    private final ChecklistRepository checklistRepository;
    private final ChecklistService checklistService;

    public TaggedProcedureLinkService(TaggedProcedureLinkRepository repository,
                                      ChecklistRepository checklistRepository,
                                      ChecklistService checklistService) {
        this.repository = repository;
        this.checklistRepository = checklistRepository;
        this.checklistService = checklistService;
    }

    /** Tag at Procedure level — creates the reusable template link. */
    public TaggedProcedureLinkResponse create(CreateLinkRequest request) {
        UUID orgId = OrgContext.getOrgId();
        if (repository.exists(orgId, request.procedureId(), request.targetType(), request.targetId())) {
            throw new BusinessRuleException("That procedure is already tagged to this "
                    + request.targetType().name().toLowerCase());
        }
        TaggedProcedureLink link = new TaggedProcedureLink();
        link.setId(UUID.randomUUID());
        link.setOrgId(orgId);
        link.setCreatedBy(OrgContext.getUserId());
        link.setProcedureId(request.procedureId());
        link.setProcedureName(request.procedureName());
        link.setTargetType(request.targetType());
        link.setTargetId(request.targetId());
        link.setTargetName(request.targetName());
        link.setCreatedAt(OffsetDateTime.now());
        return toResponse(repository.save(link));
    }

    public List<TaggedProcedureLinkResponse> list(TargetType targetType, UUID targetId) {
        return repository.findAllByOrgId(OrgContext.getOrgId(), targetType, targetId).stream()
                .map(this::toResponse).toList();
    }

    /**
     * Fill the template: spawns a FRESH checklist each time; the link stays,
     * so the template remains available in To-Do (per the VDMS doc).
     */
    public ChecklistResponse fill(UUID linkId, FillRequest request) {
        TaggedProcedureLink link = repository.findByIdAndOrgId(linkId, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Tagged procedure not found: " + linkId));
        Checklist c = newChecklist(link.getId(), "Tagged: " + link.getTargetName(),
                link.getProcedureId(), link.getProcedureName(),
                link.getTargetType(), link.getTargetId(), link.getTargetName(),
                request != null ? request.assigneeEmail() : null,
                request != null ? request.dueDate() : null,
                "Filled from tagged-procedure template on " + link.getTargetName());
        checklistRepository.save(c);
        return checklistService.get(c.getId());
    }

    /**
     * One-time "Add Procedure" on the asset/location: creates a single
     * checklist immediately and stores NO template.
     */
    public ChecklistResponse oneTime(OneTimeRequest request) {
        Checklist c = newChecklist(UUID.randomUUID(), "One-time: " + request.targetName(),
                request.procedureId(), request.procedureName(),
                request.targetType(), request.targetId(), request.targetName(),
                request.assigneeEmail(), request.dueDate(),
                "One-time procedure added on " + request.targetName());
        checklistRepository.save(c);
        return checklistService.get(c.getId());
    }

    public void delete(UUID id) {
        TaggedProcedureLink link = repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Tagged procedure not found: " + id));
        repository.delete(link);
    }

    private Checklist newChecklist(UUID lineageId, String lineageName,
                                   UUID procedureId, String procedureName,
                                   TargetType targetType, UUID targetId, String targetName,
                                   String assigneeEmail, OffsetDateTime dueDate, String historyDetail) {
        OffsetDateTime now = OffsetDateTime.now();
        Checklist c = new Checklist();
        c.setId(UUID.randomUUID());
        c.setOrgId(OrgContext.getOrgId());
        c.setConfigId(lineageId);
        c.setConfigName(lineageName);
        c.setTaggedProcedureId(lineageId);
        c.setProcedureId(procedureId);
        c.setProcedureName(procedureName);
        c.setTargetType(targetType);
        c.setTargetId(targetId);
        c.setTargetName(targetName);
        c.setAssigneeEmail(resolveAssignee(assigneeEmail));
        c.setStatus(ChecklistStatus.TODO);
        c.setSource(ChecklistSource.TAGGED_PROCEDURE);
        c.setDueDate(dueDate);
        c.setCheckInRequired(false);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c.log("TAGGED", historyDetail, now);
        return c;
    }

    private static String resolveAssignee(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        String email = OrgContext.getEmail();
        return email != null && !email.isBlank() ? email : "unassigned@sclera.local";
    }

    private TaggedProcedureLinkResponse toResponse(TaggedProcedureLink l) {
        return new TaggedProcedureLinkResponse(
                l.getId(), l.getOrgId(), l.getProcedureId(), l.getProcedureName(),
                l.getTargetType(), l.getTargetId(), l.getTargetName(),
                l.getCreatedBy(), l.getCreatedAt());
    }
}
