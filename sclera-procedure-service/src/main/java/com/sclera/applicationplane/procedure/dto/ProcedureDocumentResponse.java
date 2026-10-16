package com.sclera.applicationplane.procedure.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A library document as served to clients. The bytes are never serialised
 * here — the same rule {@code AiDocumentResponse} already follows for {@code
 * ai_document.content} — a caller fetches them from {@code
 * GET /{id}/content} instead.
 */
public record ProcedureDocumentResponse(
        UUID id,
        UUID propertyId,
        String name,
        String mimeType,
        Long sizeBytes,
        boolean active,
        UUID uploadedBy,
        OffsetDateTime uploadedAt
) {
}
