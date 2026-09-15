package com.sclera.applicationplane.inspection.dto;

import com.sclera.applicationplane.inspection.domain.TargetType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Request/response records for inspection tagging (grouped in one file). */
public final class TaggingDtos {

    private TaggingDtos() {}

    // ── Requests ──────────────────────────────────────────────────────────

    public record TagProcedureRequest(
            @NotNull UUID procedureId,
            @NotBlank @Size(max = 200) String procedureName
    ) {}

    public record TargetRequest(
            @NotNull TargetType targetType,
            @NotNull UUID targetId,
            @NotBlank @Size(max = 200) String targetName,
            @Size(max = 1000) String condition,
            @Email @Size(max = 255) String assigneeEmail
    ) {}

    public record TargetUpdateRequest(
            @Size(max = 1000) String condition,
            @Email @Size(max = 255) String assigneeEmail
    ) {}

    public record OuterConditionRequest(
            @NotBlank @Size(max = 1000) String description,
            boolean emailAlert,
            boolean createWorkOrder
    ) {}

    // ── Responses ─────────────────────────────────────────────────────────

    public record TargetResponse(
            UUID id,
            TargetType targetType,
            UUID targetId,
            String targetName,
            String condition,
            String assigneeEmail
    ) {}

    public record TaggedProcedureResponse(
            UUID id,
            UUID procedureId,
            String procedureName,
            List<TargetResponse> targets
    ) {}

    public record OuterConditionResponse(
            UUID id,
            String description,
            boolean emailAlert,
            boolean createWorkOrder
    ) {}

    public record TaggingResponse(
            UUID configId,
            List<TaggedProcedureResponse> taggedProcedures,
            List<OuterConditionResponse> outerConditions
    ) {}
}
