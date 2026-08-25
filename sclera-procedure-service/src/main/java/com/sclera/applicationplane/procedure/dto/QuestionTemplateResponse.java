package com.sclera.applicationplane.procedure.dto;

import com.sclera.applicationplane.procedure.domain.QuestionType;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full template representation. Also served on the internal Dapr endpoint as the
 * snapshot the inspection-service copies when an inspection is created — keep the
 * two services' field shapes in sync.
 */
public record QuestionTemplateResponse(
        UUID id,
        UUID orgId,
        String name,
        String description,
        String category,
        TemplateStatus status,
        int version,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<SectionResponse> sections
) {
    public record SectionResponse(
            UUID id,
            String title,
            int displayOrder,
            List<QuestionResponse> questions
    ) {}

    public record QuestionResponse(
            UUID id,
            String text,
            String helpText,
            QuestionType type,
            boolean required,
            int displayOrder,
            List<String> options,
            Integer scoreWeight
    ) {}
}
