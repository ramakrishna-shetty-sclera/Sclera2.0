package com.sclera.applicationplane.procedure.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to Kafka topic {@code sclera.procedure.global-template-events.v1}
 * when a Sclera-wide template gets a new published version. Consumers read the
 * JSON shape, not this class, so adding a field is safe.
 *
 * <p>The shape is fixed by the plan so that the branch that publishes it and
 * the branch that listens for it can be written independently.
 */
public record GlobalTemplateEvent(
        UUID eventId,
        EventType eventType,
        UUID globalTemplateId,
        UUID versionId,
        Integer versionNo,
        OffsetDateTime occurredAt
) {
    public enum EventType { PUBLISHED }

    public static final String TOPIC = "sclera.procedure.global-template-events.v1";
}
