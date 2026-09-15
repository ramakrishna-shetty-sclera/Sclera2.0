package com.sclera.applicationplane.inspection.controller;

import com.sclera.applicationplane.inspection.dto.TaggingDtos.OuterConditionRequest;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.OuterConditionResponse;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.TagProcedureRequest;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.TaggedProcedureResponse;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.TaggingResponse;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.TargetRequest;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.TargetResponse;
import com.sclera.applicationplane.inspection.dto.TaggingDtos.TargetUpdateRequest;
import com.sclera.applicationplane.inspection.service.InspectionTaggingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tagging sub-resource of an inspection configuration: procedures tagged to the
 * inspection, their asset/location targets (each with condition + assignee), and
 * inspection-wide outer conditions.
 *
 * Authorization: mutations require the org-level can_manage_inspections
 * permission (OpenFGA); reads require can_view.
 */
@RestController
@RequestMapping("/api/v1/inspection-configs/{configId}")
@PreAuthorize("@fga.checkOrg('can_manage_inspections')")
public class InspectionTaggingController {

    private final InspectionTaggingService service;

    public InspectionTaggingController(InspectionTaggingService service) {
        this.service = service;
    }

    @GetMapping("/tagging")
    @PreAuthorize("@fga.checkOrg('can_view')")   // read-only: overrides the class-level rule
    public TaggingResponse get(@PathVariable UUID configId) {
        return service.get(configId);
    }

    @PostMapping("/tagged-procedures")
    @ResponseStatus(HttpStatus.CREATED)
    public TaggedProcedureResponse tagProcedure(@PathVariable UUID configId,
                                                @Valid @RequestBody TagProcedureRequest request) {
        return service.tagProcedure(configId, request);
    }

    @DeleteMapping("/tagged-procedures/{taggedProcedureId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void untagProcedure(@PathVariable UUID configId, @PathVariable UUID taggedProcedureId) {
        service.untagProcedure(configId, taggedProcedureId);
    }

    @PostMapping("/tagged-procedures/{taggedProcedureId}/targets")
    @ResponseStatus(HttpStatus.CREATED)
    public TargetResponse addTarget(@PathVariable UUID configId,
                                    @PathVariable UUID taggedProcedureId,
                                    @Valid @RequestBody TargetRequest request) {
        return service.addTarget(configId, taggedProcedureId, request);
    }

    @PutMapping("/tagged-procedures/{taggedProcedureId}/targets/{targetId}")
    public TargetResponse updateTarget(@PathVariable UUID configId,
                                       @PathVariable UUID taggedProcedureId,
                                       @PathVariable UUID targetId,
                                       @Valid @RequestBody TargetUpdateRequest request) {
        return service.updateTarget(configId, taggedProcedureId, targetId, request);
    }

    @DeleteMapping("/tagged-procedures/{taggedProcedureId}/targets/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTarget(@PathVariable UUID configId,
                             @PathVariable UUID taggedProcedureId,
                             @PathVariable UUID targetId) {
        service.removeTarget(configId, taggedProcedureId, targetId);
    }

    @PostMapping("/conditions")
    @ResponseStatus(HttpStatus.CREATED)
    public OuterConditionResponse addCondition(@PathVariable UUID configId,
                                               @Valid @RequestBody OuterConditionRequest request) {
        return service.addCondition(configId, request);
    }

    @DeleteMapping("/conditions/{conditionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCondition(@PathVariable UUID configId, @PathVariable UUID conditionId) {
        service.removeCondition(configId, conditionId);
    }
}
