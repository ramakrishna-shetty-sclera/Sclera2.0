package com.sclera.applicationplane.inspection.dto;

import com.sclera.applicationplane.inspection.domain.Frequency;
import com.sclera.applicationplane.inspection.domain.Priority;
import com.sclera.applicationplane.inspection.domain.Weekday;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Create/update payload for the inspection configuration form.
 * Cross-field rules (secondary != primary, unique code) are enforced in the service.
 */
public record InspectionConfigRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 50) String code,
        @Size(max = 2000) String description,
        @NotBlank @Email @Size(max = 255) String assigneeEmail,
        @Email @Size(max = 255) String secondaryAssigneeEmail,
        @Size(max = 100) String category,
        Priority priority,
        @NotNull Frequency frequency,
        @NotEmpty Set<Weekday> scheduleDays,
        boolean bypassScan,
        boolean enableCheckInOut,
        boolean enablePoints,
        boolean mergedView
) {}
