package com.sclera.applicationplane.procedure.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to Kafka topic {@code sclera.procedure.template-events.v1} on every
 * template lifecycle transition. Consumed by inspection-service (and anyone else).
 */
public record QuestionTemplateEvent(
        UUID eventId,
        EventType eventType,
        UUID templateId,
        UUID orgId,
        String name,
        int version,
        OffsetDateTime occurredAt
) {
    public enum EventType { PUBLISHED, UPDATED, ARCHIVED }

    public static final String TOPIC = "sclera.procedure.template-events.v1";
}
