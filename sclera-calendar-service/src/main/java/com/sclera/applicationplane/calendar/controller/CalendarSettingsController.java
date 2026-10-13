package com.sclera.applicationplane.calendar.controller;

import com.sclera.applicationplane.calendar.dto.CalendarDtos.EffectiveSettingsResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.SettingsRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.SettingsResponse;
import com.sclera.applicationplane.calendar.service.CalendarSettingsService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Date/time configuration per property (propertyId null = org-wide default).
 * Responses are wrapped in the standard envelope by sclera-common.
 */
@RestController
@RequestMapping("/api/v1/calendar/settings")
public class CalendarSettingsController {

    private final CalendarSettingsService service;

    public CalendarSettingsController(CalendarSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<SettingsResponse> list() {
        return service.list();
    }

    /** Resolved settings: property row → org default → built-in defaults. */
    @GetMapping("/effective")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public EffectiveSettingsResponse effective(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.effectiveResponse(propertyId, date);
    }

    /** Upsert keyed by propertyId in the body (null = org-wide default). */
    @PutMapping
    @PreAuthorize("@fga.checkOrg('can_manage_calendar')")
    public SettingsResponse upsert(@Valid @RequestBody SettingsRequest request) {
        return service.upsert(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@fga.checkOrg('can_manage_calendar')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
