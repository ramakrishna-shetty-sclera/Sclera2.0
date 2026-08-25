package com.sclera.applicationplane.inspection.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Published to Kafka when an inspection reaches COMPLETED. */
public record InspectionCompletedEvent(
        UUID eventId,
        UUID inspectionId,
        UUID orgId,
        UUID templateId,
        int templateVersion,
        UUID completedBy,
        int answerCount,
        OffsetDateTime completedAt
) {
    public static final String TOPIC = "sclera.inspection.events.v1";
}
