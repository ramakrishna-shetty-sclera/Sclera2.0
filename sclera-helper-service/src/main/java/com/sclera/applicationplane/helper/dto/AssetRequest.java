package com.sclera.applicationplane.helper.dto;

import com.sclera.applicationplane.helper.domain.AssetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Create/update payload for an asset.
 * ipAddress is required when assetType is IP and must be absent for NON_IP
 * (enforced in the service). locationId is optional; when present it must
 * reference an existing location.
 */
public record AssetRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull AssetType assetType,
        @Size(max = 45) String ipAddress,
        UUID locationId
) {}
