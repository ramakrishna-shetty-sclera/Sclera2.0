package com.sclera.applicationplane.inspection.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateInspectionRequest(
        @NotNull UUID templateId,
        UUID assigneeId,
        OffsetDateTime scheduledFor,
        @Size(max = 4000) String notes
) {}
