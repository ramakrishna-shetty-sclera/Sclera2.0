package com.sclera.applicationplane.helper.dto;

import com.sclera.applicationplane.helper.domain.LocationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Create/update payload for a location node.
 * parentId is required for FLOOR and LOCATION, and must be null for BUILDING
 * (enforced in the service).
 */
public record LocationRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull LocationType type,
        UUID parentId
) {}
