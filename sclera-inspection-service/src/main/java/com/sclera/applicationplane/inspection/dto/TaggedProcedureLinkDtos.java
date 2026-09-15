package com.sclera.applicationplane.inspection.dto;

import com.sclera.applicationplane.inspection.domain.TargetType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response records for tagged procedures on assets/locations. */
public final class TaggedProcedureLinkDtos {

    private TaggedProcedureLinkDtos() {}

    public record CreateLinkRequest(
            @NotNull UUID procedureId,
            @NotBlank @Size(max = 200) String procedureName,
            @NotNull TargetType targetType,
            @NotNull UUID targetId,
            @NotBlank @Size(max = 200) String targetName
    ) {}

    public record FillRequest(
            @Email @Size(max = 255) String assigneeEmail,
            OffsetDateTime dueDate
    ) {}

    /** One-time "Add Procedure": creates a single checklist, stores no template. */
    public record OneTimeRequest(
            @NotNull UUID procedureId,
            @NotBlank @Size(max = 200) String procedureName,
            @NotNull TargetType targetType,
            @NotNull UUID targetId,
            @NotBlank @Size(max = 200) String targetName,
            @Email @Size(max = 255) String assigneeEmail,
            OffsetDateTime dueDate
    ) {}

    public record TaggedProcedureLinkResponse(
            UUID id,
            UUID orgId,
            UUID procedureId,
            String procedureName,
            TargetType targetType,
            UUID targetId,
            String targetName,
            UUID createdBy,
            OffsetDateTime createdAt
    ) {}
}
