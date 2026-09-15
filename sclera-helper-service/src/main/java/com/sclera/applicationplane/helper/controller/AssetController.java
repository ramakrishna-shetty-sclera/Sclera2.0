package com.sclera.applicationplane.helper.controller;

import com.sclera.applicationplane.helper.domain.AssetType;
import com.sclera.applicationplane.helper.dto.AssetRequest;
import com.sclera.applicationplane.helper.dto.AssetResponse;
import com.sclera.applicationplane.helper.service.AssetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * Tenant-facing CRUD for assets (IP / non-IP, optionally tagged to a location).
 * Responses are wrapped in the standard envelope by sclera-common.
 */
@RestController
@RequestMapping("/api/v1/helper/assets")
public class AssetController {

    private final AssetService service;

    public AssetController(AssetService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssetResponse create(@Valid @RequestBody AssetRequest request) {
        return service.create(request);
    }

    /**
     * @param assetType  optional filter by IP / NON_IP
     * @param locationId optional filter by tagged location
     * @param untagged   optional: true = only assets with no location, false = only tagged
     */
    @GetMapping
    public List<AssetResponse> list(@RequestParam(required = false) AssetType assetType,
                                    @RequestParam(required = false) UUID locationId,
                                    @RequestParam(required = false) Boolean untagged) {
        return service.list(assetType, locationId, untagged);
    }

    @GetMapping("/{id}")
    public AssetResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public AssetResponse update(@PathVariable UUID id, @Valid @RequestBody AssetRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
