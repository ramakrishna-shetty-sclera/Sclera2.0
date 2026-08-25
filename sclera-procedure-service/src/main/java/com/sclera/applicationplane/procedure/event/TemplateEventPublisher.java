package com.sclera.applicationplane.procedure.event;

import com.sclera.applicationplane.procedure.domain.QuestionTemplate;
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

    public void publish(QuestionTemplate template, QuestionTemplateEvent.EventType type) {
        QuestionTemplateEvent event = new QuestionTemplateEvent(
                UUID.randomUUID(),
                type,
                template.getId(),
                template.getOrgId(),
                template.getName(),
                template.getVersion(),
                OffsetDateTime.now());

        // Key by templateId so all events for one template stay ordered in a partition
        kafkaTemplate.send(QuestionTemplateEvent.TOPIC, template.getId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish template event {} for template {}: {}",
                                type, template.getId(), ex.getMessage(), ex);
                    } else {
                        log.info("Published template event {} for template {} v{}",
                                type, template.getId(), template.getVersion());
                    }
                });
    }
}
