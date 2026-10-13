package com.sclera.applicationplane.calendar.controller;

import com.sclera.applicationplane.calendar.dto.CalendarDtos.HolidayImportRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.HolidayRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.HolidayResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.OverrideRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.OverrideResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.ResolvedHolidayResponse;
import com.sclera.applicationplane.calendar.service.HolidayService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * Holiday definitions (fixed / floating / one-off), regional imports and
 * per-property overrides, plus resolution to concrete dates.
 */
@RestController
@RequestMapping("/api/v1/calendar/holidays")
public class HolidayController {

    private final HolidayService service;

    public HolidayController(HolidayService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.checkOrg('can_manage_calendar')")
    public HolidayResponse create(@Valid @RequestBody HolidayRequest request) {
        return service.create(request);
    }

    @GetMapping
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<HolidayResponse> list(@RequestParam(required = false) UUID propertyId) {
        return service.list(propertyId);
    }

    /** Concrete dates in [from, to] for a property, after overrides. */
    @GetMapping("/resolved")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<ResolvedHolidayResponse> resolved(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.resolved(propertyId, from, to);
    }

    /** Imports a bundled regional pack (US, GB, IN), replacing a prior import of it. */
    @PostMapping("/import")
    @PreAuthorize("@fga.checkOrg('can_manage_calendar')")
    public List<HolidayResponse> importRegion(@Valid @RequestBody HolidayImportRequest request) {
        return service.importRegion(request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public HolidayResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@fga.checkOrg('can_manage_calendar')")
    public HolidayResponse update(@PathVariable UUID id, @Valid @RequestBody HolidayRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@fga.checkOrg('can_manage_calendar')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    // ── per-property overrides ──────────────────────────────────────────────

    @GetMapping("/{id}/overrides")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<OverrideResponse> listOverrides(@PathVariable UUID id) {
        return service.listOverrides(id);
    }

    @PostMapping("/{id}/overrides")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.checkOrg('can_manage_calendar')")
    public OverrideResponse addOverride(@PathVariable UUID id, @Valid @RequestBody OverrideRequest request) {
        return service.addOverride(id, request);
    }

    @DeleteMapping("/overrides/{overrideId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@fga.checkOrg('can_manage_calendar')")
    public void deleteOverride(@PathVariable UUID overrideId) {
        service.deleteOverride(overrideId);
    }
}
