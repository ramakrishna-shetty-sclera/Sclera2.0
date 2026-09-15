package com.sclera.applicationplane.helper.dto;

import com.sclera.applicationplane.helper.domain.LocationType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LocationResponse(
        UUID id,
        UUID orgId,
        String name,
        LocationType type,
        UUID parentId,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
