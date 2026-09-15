package com.sclera.applicationplane.inspection.controller;

import com.sclera.applicationplane.inspection.dto.InspectionConfigRequest;
import com.sclera.applicationplane.inspection.dto.InspectionConfigResponse;
import com.sclera.applicationplane.inspection.service.InspectionConfigService;
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

import java.util.List;
import java.util.UUID;

/**
 * Inspection configuration CRUD (the VDMS "create inspection" form).
 * In-memory / dummy for now; responses wrapped by sclera-common's envelope.
 *
 * Authorization: org-level OpenFGA role checks (per-object tuples are only
 * written for DB-backed resources; these are in-memory).
 */
@RestController
@RequestMapping("/api/v1/inspection-configs")
public class InspectionConfigController {

    private final InspectionConfigService service;

    public InspectionConfigController(InspectionConfigService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.checkOrg('can_manage_inspections')")
    public InspectionConfigResponse create(@Valid @RequestBody InspectionConfigRequest request) {
        return service.create(request);
    }

    @GetMapping
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<InspectionConfigResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public InspectionConfigResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@fga.checkOrg('can_manage_inspections')")
    public InspectionConfigResponse update(@PathVariable UUID id,
                                           @Valid @RequestBody InspectionConfigRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@fga.checkOrg('can_manage_inspections')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
