package com.sclera.applicationplane.procedure.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A library document as served to clients. {@code location} is the opaque
 * storage key, not a URL — the caller exchanges it for a fresh download link
 * through the helper service's own endpoint, since a signed URL expires and a
 * stored one would be a dead link by the time anyone followed it.
 */
public record ProcedureDocumentResponse(
        UUID id,
        UUID propertyId,
        String name,
        String mimeType,
        Long sizeBytes,
        String location,
        boolean active,
        UUID uploadedBy,
        OffsetDateTime uploadedAt
) {
}
