package com.sclera.applicationplane.inspection.dto;

import com.sclera.applicationplane.inspection.domain.ChecklistSource;
import com.sclera.applicationplane.inspection.domain.ChecklistStatus;
import com.sclera.applicationplane.inspection.domain.TargetType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response records for checklists (grouped in one file). */
public final class ChecklistDtos {

    private ChecklistDtos() {}

    // ── Requests ──────────────────────────────────────────────────────────

    public record GenerateRequest(
            @NotNull UUID configId,
            OffsetDateTime dueDate
    ) {}

    public record AnswerInput(
            @NotNull UUID questionId,
            @Size(max = 8000) String value,
            boolean failed,
            @Size(max = 2000) String comment
    ) {}

    public record SaveAnswersRequest(
            @Valid List<AnswerInput> answers,
            boolean checkout
    ) {}

    public record ExceptionRequest(
            @NotBlank @Size(max = 1000) String reason
    ) {}

    public record AssigneeRequest(
            @NotBlank @Email @Size(max = 255) String assigneeEmail
    ) {}

    public record WorkOrderRequest(
            @Size(max = 1000) String note
    ) {}

    // ── Responses ─────────────────────────────────────────────────────────

    public record AnswerResponse(
            UUID questionId,
            String value,
            boolean failed,
            String comment
    ) {}

    public record HistoryResponse(
            OffsetDateTime at,
            String action,
            String detail
    ) {}

    public record ChecklistResponse(
            UUID id,
            UUID orgId,
            UUID configId,
            String configName,
            UUID taggedProcedureId,
            UUID procedureId,
            String procedureName,
            TargetType targetType,
            UUID targetId,
            String targetName,
            String assigneeEmail,
            ChecklistStatus status,
            ChecklistSource source,
            OffsetDateTime dueDate,
            boolean checkInRequired,
            OffsetDateTime checkInAt,
            OffsetDateTime checkOutAt,
            String exceptionReason,
            List<AnswerResponse> answers,
            List<HistoryResponse> history,
            List<UUID> workOrderIds,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}
}
