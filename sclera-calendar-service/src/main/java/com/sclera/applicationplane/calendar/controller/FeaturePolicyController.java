package com.sclera.applicationplane.calendar.controller;

import com.sclera.applicationplane.calendar.dto.CalendarDtos.PolicyRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.PolicyResponse;
import com.sclera.applicationplane.calendar.service.FeaturePolicyService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Feature opt-in: which calendar layers (working hours / downtime / holidays)
 * a feature code respects, and which downtime types / holiday categories
 * affect it. A feature without a policy respects everything.
 */
@RestController
@RequestMapping("/api/v1/calendar/policies")
public class FeaturePolicyController {

    private final FeaturePolicyService service;

    public FeaturePolicyController(FeaturePolicyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<PolicyResponse> list() {
        return service.list();
    }

    /** Upsert keyed by (featureCode, propertyId). */
    @PutMapping
    @PreAuthorize("@fga.checkOrg('can_manage_calendar')")
    public PolicyResponse upsert(@Valid @RequestBody PolicyRequest request) {
        return service.upsert(request);
    }
}
