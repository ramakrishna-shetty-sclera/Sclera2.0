package com.sclera.applicationplane.calendar.controller;

import com.sclera.applicationplane.calendar.dto.CalendarDtos.DowntimeRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.DowntimeResponse;
import com.sclera.applicationplane.calendar.service.DowntimeService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Downtime windows: one-off (absolute instants) or recurring (wall-clock rule
 * in the property timezone), scoped to the whole property or a sub-resource.
 */
@RestController
@RequestMapping("/api/v1/calendar/downtimes")
public class DowntimeController {

    private final DowntimeService service;

    public DowntimeController(DowntimeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.checkOrg('can_manage_calendar')")
    public DowntimeResponse create(@Valid @RequestBody DowntimeRequest request) {
        return service.create(request);
    }

    @GetMapping
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<DowntimeResponse> list(@RequestParam(required = false) UUID propertyId,
                                       @RequestParam(required = false) Boolean active) {
        return service.list(propertyId, active);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public DowntimeResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@fga.checkOrg('can_manage_calendar')")
    public DowntimeResponse update(@PathVariable UUID id, @Valid @RequestBody DowntimeRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@fga.checkOrg('can_manage_calendar')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
