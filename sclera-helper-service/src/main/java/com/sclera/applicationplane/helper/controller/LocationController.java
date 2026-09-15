package com.sclera.applicationplane.helper.controller;

import com.sclera.applicationplane.helper.domain.LocationType;
import com.sclera.applicationplane.helper.dto.LocationRequest;
import com.sclera.applicationplane.helper.dto.LocationResponse;
import com.sclera.applicationplane.helper.service.LocationService;
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
 * Tenant-facing CRUD for locations (building → floor → location).
 * Responses are wrapped in the standard envelope by sclera-common.
 */
@RestController
@RequestMapping("/api/v1/helper/locations")
public class LocationController {

    private final LocationService service;

    public LocationController(LocationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.checkOrg('can_manage_assets')")
    public LocationResponse create(@Valid @RequestBody LocationRequest request) {
        return service.create(request);
    }

    @GetMapping
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<LocationResponse> list(@RequestParam(required = false) LocationType type,
                                       @RequestParam(required = false) UUID parentId) {
        return service.list(type, parentId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public LocationResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@fga.checkOrg('can_manage_assets')")
    public LocationResponse update(@PathVariable UUID id, @Valid @RequestBody LocationRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@fga.checkOrg('can_manage_assets')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
