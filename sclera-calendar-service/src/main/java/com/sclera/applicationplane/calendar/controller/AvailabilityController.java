package com.sclera.applicationplane.calendar.controller;

import com.sclera.applicationplane.calendar.domain.DowntimeWindow;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.AvailabilityResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.NextSlotResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.UnavailableRangeResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.WorkingHoursResponse;
import com.sclera.applicationplane.calendar.service.AvailabilityService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The availability queries features consult instead of hardcoding date/time
 * rules. Pass featureCode to apply that feature's opt-in policy; omit it to
 * respect every layer. Timestamps come back in the property's local offset.
 */
@RestController
@RequestMapping("/api/v1/calendar/availability")
public class AvailabilityController {

    private final AvailabilityService service;

    public AvailabilityController(AvailabilityService service) {
        this.service = service;
    }

    @GetMapping("/check")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public AvailabilityResponse check(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at,
            @RequestParam(required = false) String featureCode,
            @RequestParam(required = false) DowntimeWindow.ScopeType scopeType,
            @RequestParam(required = false) UUID scopeId) {
        return service.check(propertyId, at, featureCode, scopeType, scopeId);
    }

    @GetMapping("/next-slot")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public NextSlotResponse nextSlot(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam int durationMinutes,
            @RequestParam(required = false) String featureCode,
            @RequestParam(required = false) DowntimeWindow.ScopeType scopeType,
            @RequestParam(required = false) UUID scopeId) {
        return service.nextSlot(propertyId, from, durationMinutes, featureCode, scopeType, scopeId);
    }

    @GetMapping("/unavailable")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<UnavailableRangeResponse> unavailable(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String featureCode,
            @RequestParam(required = false) DowntimeWindow.ScopeType scopeType,
            @RequestParam(required = false) UUID scopeId) {
        return service.unavailableRanges(propertyId, from, to, featureCode, scopeType, scopeId);
    }

    @GetMapping("/working-hours")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public WorkingHoursResponse workingHours(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.workingHours(propertyId, date);
    }
}
