package com.sclera.applicationplane.procedure.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to Kafka topic {@code sclera.procedure.template-events.v1} on every
 * template lifecycle transition. Consumers read the JSON shape, not this class,
 * so adding a field is safe.
 *
 * versionId / versionNo / definitionHash identify the version the event is
 * about: the newly published one, or on ARCHIVED the current published one
 * (null if the template was never published).
 */
public record ProcedureTemplateEvent(
        UUID eventId,
        EventType eventType,
        UUID templateId,
        UUID orgId,
        String name,
        UUID versionId,
        Integer versionNo,
        String definitionHash,
        OffsetDateTime occurredAt
) {
    public enum EventType { PUBLISHED, ARCHIVED }

    public static final String TOPIC = "sclera.procedure.template-events.v1";
}
