package com.sclera.applicationplane.procedure.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A result type as served to clients. Consumers store {@code key} and resolve
 * name and colour through this endpoint, so a rename or recolour reaches
 * history rather than leaving old records on stale labels.
 */
public record ResultTypeResponse(
        UUID id,
        String key,
        String name,
        String color,
        String description,
        int severityOrder,
        boolean system,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
