package com.sclera.applicationplane.inspection.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes template lifecycle events from procedure-service.
 * Read as a JsonNode to stay decoupled from the producer's class — the contract
 * is the JSON shape, not a shared Java type.
 */
@Component
public class TemplateEventListener {

    private static final Logger log = LoggerFactory.getLogger(TemplateEventListener.class);

    @KafkaListener(
            topics = "sclera.procedure.template-events.v1",
            groupId = "${spring.application.name}")
    public void onTemplateEvent(JsonNode event) {
        String eventType = event.path("eventType").asText();
        String templateId = event.path("templateId").asText();
        int version = event.path("version").asInt();
        log.info("Received template event {} for template {} v{}", eventType, templateId, version);

        // Existing inspections keep their snapshot, so nothing to rewrite here.
        // Extension points: invalidate a local template cache, notify assignees of
        // ARCHIVED templates, or pre-warm the new PUBLISHED version.
    }
}
