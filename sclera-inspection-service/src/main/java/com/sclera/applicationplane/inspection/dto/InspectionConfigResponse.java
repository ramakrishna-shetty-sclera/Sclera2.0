package com.sclera.applicationplane.inspection.dto;

import com.sclera.applicationplane.inspection.domain.Frequency;
import com.sclera.applicationplane.inspection.domain.Priority;
import com.sclera.applicationplane.inspection.domain.Weekday;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record InspectionConfigResponse(
        UUID id,
        UUID orgId,
        String name,
        String code,
        String description,
        String assigneeEmail,
        String secondaryAssigneeEmail,
        String category,
        Priority priority,
        Frequency frequency,
        Set<Weekday> scheduleDays,
        boolean bypassScan,
        boolean enableCheckInOut,
        boolean enablePoints,
        boolean mergedView,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
