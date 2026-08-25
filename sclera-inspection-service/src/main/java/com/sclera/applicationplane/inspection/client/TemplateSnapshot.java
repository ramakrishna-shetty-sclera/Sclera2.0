package com.sclera.applicationplane.inspection.client;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape of procedure-service's internal template endpoint
 * (GET /internal/api/v1/question-templates/{id}) — keep in sync with
 * QuestionTemplateResponse over there.
 */
public record TemplateSnapshot(
        UUID id,
        UUID orgId,
        String name,
        String description,
        String category,
        String status,
        int version,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<SectionSnapshot> sections
) {
    public record SectionSnapshot(
            UUID id,
            String title,
            int displayOrder,
            List<QuestionSnapshot> questions
    ) {}

    public record QuestionSnapshot(
            UUID id,
            String text,
            String helpText,
            String type,
            boolean required,
            int displayOrder,
            List<String> options,
            Integer scoreWeight
    ) {}
}
