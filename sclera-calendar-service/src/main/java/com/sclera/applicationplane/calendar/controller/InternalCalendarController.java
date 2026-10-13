package com.sclera.applicationplane.calendar.controller;

import com.sclera.applicationplane.calendar.domain.DowntimeWindow;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.AvailabilityResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.NextSlotResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.WorkingHoursResponse;
import com.sclera.applicationplane.calendar.service.AvailabilityService;
import com.sclera.applicationplane.calendar.tenancy.TenantContext;
import com.sclera.applicationplane.calendar.tenancy.TenantRegistryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Availability queries for service-to-service callers (Dapr + HMAC — the
 * InternalEndpointFilter from sclera-common guards /internal/**). POST with a
 * body because Dapr rejects '?' in the invocation method name, and the org
 * comes from the path because there is no JWT on this hop; work runs inside
 * TenantContext.runAs so the schema-per-tenant layer resolves correctly.
 */
@RestController
@RequestMapping("/internal/api/v1/calendar/orgs/{orgId}")
public class InternalCalendarController {

    public record CheckQuery(UUID propertyId, @NotNull OffsetDateTime at, String featureCode,
                             DowntimeWindow.ScopeType scopeType, UUID scopeId) {}

    public record NextSlotQuery(UUID propertyId, @NotNull OffsetDateTime from,
                                @Min(1) int durationMinutes, String featureCode,
                                DowntimeWindow.ScopeType scopeType, UUID scopeId) {}

    public record WorkingHoursQuery(UUID propertyId, @NotNull LocalDate date) {}

    private final AvailabilityService service;
    private final TenantRegistryService tenantRegistry;

    public InternalCalendarController(AvailabilityService service, TenantRegistryService tenantRegistry) {
        this.service = service;
        this.tenantRegistry = tenantRegistry;
    }

    @PostMapping("/availability/check")
    public AvailabilityResponse check(@PathVariable UUID orgId, @Valid @RequestBody CheckQuery query) {
        return runAs(orgId, () -> service.check(query.propertyId(), query.at(),
                query.featureCode(), query.scopeType(), query.scopeId()));
    }

    @PostMapping("/availability/next-slot")
    public NextSlotResponse nextSlot(@PathVariable UUID orgId, @Valid @RequestBody NextSlotQuery query) {
        return runAs(orgId, () -> service.nextSlot(query.propertyId(), query.from(),
                query.durationMinutes(), query.featureCode(), query.scopeType(), query.scopeId()));
    }

    @PostMapping("/availability/working-hours")
    public WorkingHoursResponse workingHours(@PathVariable UUID orgId,
                                             @Valid @RequestBody WorkingHoursQuery query) {
        return runAs(orgId, () -> service.workingHours(query.propertyId(), query.date()));
    }

    private <T> T runAs(UUID orgId, Supplier<T> work) {
        // the JWT-driven TenantProvisioningFilter doesn't see this hop
        tenantRegistry.ensureTenant(orgId);
        return TenantContext.runAs(orgId, work);
    }
}
