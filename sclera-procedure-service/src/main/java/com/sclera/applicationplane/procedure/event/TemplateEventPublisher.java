package com.sclera.applicationplane.procedure.event;

import com.sclera.applicationplane.procedure.domain.ProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplateVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class TemplateEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TemplateEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public TemplateEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** {@code version} may be null for an ARCHIVED event on a never-published template. */
    public void publish(ProcedureTemplate template, ProcedureTemplateVersion version,
                        ProcedureTemplateEvent.EventType type) {
        ProcedureTemplateEvent event = new ProcedureTemplateEvent(
                UUID.randomUUID(),
                type,
                template.getId(),
                template.getOrgId(),
                template.getName(),
                version == null ? null : version.getId(),
                version == null ? null : version.getVersionNo(),
                version == null ? null : version.getDefinitionHash(),
                OffsetDateTime.now());

        // Key by templateId so all events for one template stay ordered in a partition
        kafkaTemplate.send(ProcedureTemplateEvent.TOPIC, template.getId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish template event {} for template {}: {}",
                                type, template.getId(), ex.getMessage(), ex);
                    } else {
                        log.info("Published template event {} for template {} v{}",
                                type, template.getId(), event.versionNo());
                    }
                });
    }
}
