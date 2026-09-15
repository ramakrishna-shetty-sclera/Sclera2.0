package com.sclera.applicationplane.inspection.service;

import com.sclera.applicationplane.inspection.domain.Checklist;
import com.sclera.applicationplane.inspection.domain.ChecklistSource;
import com.sclera.applicationplane.inspection.domain.ChecklistStatus;
import com.sclera.applicationplane.inspection.domain.ReactiveService;
import com.sclera.applicationplane.inspection.domain.TargetType;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.ChecklistResponse;
import com.sclera.applicationplane.inspection.dto.ReactiveServiceDtos.CreateReactiveServiceRequest;
import com.sclera.applicationplane.inspection.dto.ReactiveServiceDtos.RaiseRequestRequest;
import com.sclera.applicationplane.inspection.dto.ReactiveServiceDtos.ReactiveServiceResponse;
import com.sclera.applicationplane.inspection.repository.ChecklistRepository;
import com.sclera.applicationplane.inspection.repository.ReactiveServiceRepository;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ReactiveServiceService {

    private final ReactiveServiceRepository repository;
    private final ChecklistRepository checklistRepository;
    private final ChecklistService checklistService;

    public ReactiveServiceService(ReactiveServiceRepository repository,
                                  ChecklistRepository checklistRepository,
                                  ChecklistService checklistService) {
        this.repository = repository;
        this.checklistRepository = checklistRepository;
        this.checklistService = checklistService;
    }

    public ReactiveServiceResponse create(CreateReactiveServiceRequest request) {
        ReactiveService s = new ReactiveService();
        s.setId(UUID.randomUUID());
        s.setOrgId(OrgContext.getOrgId());
        s.setCreatedBy(OrgContext.getUserId());
        s.setName(request.name() == null || request.name().isBlank()
                ? request.procedureName() + " @ " + request.locationName()
                : request.name().trim());
        s.setProcedureId(request.procedureId());
        s.setProcedureName(request.procedureName());
        s.setLocationId(request.locationId());
        s.setLocationName(request.locationName());
        s.setQrToken(UUID.randomUUID().toString().replace("-", ""));
        s.setCreatedAt(OffsetDateTime.now());
        return toResponse(repository.save(s));
    }

    public List<ReactiveServiceResponse> list() {
        return repository.findAllByOrgId(OrgContext.getOrgId()).stream().map(this::toResponse).toList();
    }

    public ReactiveServiceResponse get(UUID id) {
        return toResponse(getOwned(id));
    }

    /** Resolve a scanned QR token to its service (org-scoped). */
    public ReactiveServiceResponse resolveByToken(String qrToken) {
        return repository.findByQrTokenAndOrgId(qrToken, OrgContext.getOrgId())
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("No reactive service for this QR code"));
    }

    /**
     * Raise a request: creates a To-Do checklist (source=REACTIVE_SERVICE)
     * targeting the service's location, with the raiser-chosen assignee + due
     * date. It then flows through the normal checklist lifecycle and shows up
     * in the Task Dashboard / Task Map.
     */
    public ChecklistResponse raiseRequest(UUID id, RaiseRequestRequest request) {
        ReactiveService s = getOwned(id);
        OffsetDateTime now = OffsetDateTime.now();

        Checklist c = new Checklist();
        c.setId(UUID.randomUUID());
        c.setOrgId(s.getOrgId());
        c.setConfigId(s.getId());          // lineage points at the reactive service
        c.setConfigName(s.getName());
        c.setTaggedProcedureId(s.getId());
        c.setProcedureId(s.getProcedureId());
        c.setProcedureName(s.getProcedureName());
        c.setTargetType(TargetType.LOCATION);
        c.setTargetId(s.getLocationId());
        c.setTargetName(s.getLocationName());
        c.setAssigneeEmail(request.assigneeEmail().trim());
        c.setStatus(ChecklistStatus.TODO);
        c.setSource(ChecklistSource.REACTIVE_SERVICE);
        c.setDueDate(request.dueDate());
        c.setCheckInRequired(false);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c.log("RAISED", "Request raised via reactive service " + s.getName(), now);
        checklistRepository.save(c);
        return checklistService.get(c.getId());
    }

    public void delete(UUID id) {
        repository.delete(getOwned(id));
    }

    private ReactiveService getOwned(UUID id) {
        return repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Reactive service not found: " + id));
    }

    private ReactiveServiceResponse toResponse(ReactiveService s) {
        return new ReactiveServiceResponse(
                s.getId(), s.getOrgId(), s.getName(),
                s.getProcedureId(), s.getProcedureName(),
                s.getLocationId(), s.getLocationName(),
                s.getQrToken(), s.getCreatedBy(), s.getCreatedAt());
    }
}
