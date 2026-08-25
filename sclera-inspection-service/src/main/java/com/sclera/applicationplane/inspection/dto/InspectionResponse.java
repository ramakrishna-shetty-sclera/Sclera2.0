package com.sclera.applicationplane.inspection.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.sclera.applicationplane.inspection.domain.InspectionStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record InspectionResponse(
        UUID id,
        UUID orgId,
        UUID templateId,
        int templateVersion,
        String templateName,
        JsonNode templateSnapshot,
        InspectionStatus status,
        UUID assigneeId,
        OffsetDateTime scheduledFor,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        String notes,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<AnswerResponse> answers
) {
    public record AnswerResponse(
            UUID id,
            UUID questionId,
            JsonNode value,
            Integer score,
            String comment
    ) {}
}
