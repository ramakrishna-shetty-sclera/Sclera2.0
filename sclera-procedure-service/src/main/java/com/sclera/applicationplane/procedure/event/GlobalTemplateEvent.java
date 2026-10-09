package com.sclera.applicationplane.procedure.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to Kafka topic {@code sclera.procedure.global-template-events.v1}
 * when a global template's draft freezes as a new current version. Consumers
 * read the JSON shape, not this class, so adding a field is safe.
 *
 * <p>Carries no recipient, deliberately. "Which organizations care" is a
 * one-to-many fact — every organization whose copy is still {@code LINKED} —
 * derived from {@code global_template_org_copy}, not something the publisher
 * should know or guess at. A future notification service resolves that for
 * itself from this event's {@code globalTemplateId}; nothing does yet (see
 * {@code GlobalProcedureTemplateService}'s commit history).
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
