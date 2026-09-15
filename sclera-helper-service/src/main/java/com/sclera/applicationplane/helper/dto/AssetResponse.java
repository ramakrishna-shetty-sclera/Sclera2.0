package com.sclera.applicationplane.helper.dto;

import com.sclera.applicationplane.helper.domain.AssetType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AssetResponse(
        UUID id,
        UUID orgId,
        String name,
        AssetType assetType,
        String ipAddress,
        UUID locationId,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
