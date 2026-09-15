package com.sclera.applicationplane.inspection.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response records for reactive services (grouped in one file). */
public final class ReactiveServiceDtos {

    private ReactiveServiceDtos() {}

    public record CreateReactiveServiceRequest(
            @Size(max = 200) String name,
            @NotNull UUID procedureId,
            @NotBlank @Size(max = 200) String procedureName,
            @NotNull UUID locationId,
            @NotBlank @Size(max = 200) String locationName
    ) {}

    public record RaiseRequestRequest(
            @NotBlank @Email @Size(max = 255) String assigneeEmail,
            OffsetDateTime dueDate
    ) {}

    public record ReactiveServiceResponse(
            UUID id,
            UUID orgId,
            String name,
            UUID procedureId,
            String procedureName,
            UUID locationId,
            String locationName,
            String qrToken,
            UUID createdBy,
            OffsetDateTime createdAt
    ) {}
}
