package com.sclera.applicationplane.inspection.event;

import com.sclera.applicationplane.inspection.domain.Inspection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InspectionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InspectionEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public InspectionEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishCompleted(Inspection inspection) {
        InspectionCompletedEvent event = new InspectionCompletedEvent(
                UUID.randomUUID(),
                inspection.getId(),
                inspection.getOrgId(),
                inspection.getTemplateId(),
                inspection.getTemplateVersion(),
                inspection.getCreatedBy(),
                inspection.getAnswers().size(),
                inspection.getCompletedAt());

        kafkaTemplate.send(InspectionCompletedEvent.TOPIC, inspection.getId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish completion event for inspection {}: {}",
                                inspection.getId(), ex.getMessage(), ex);
                    } else {
                        log.info("Published completion event for inspection {}", inspection.getId());
                    }
                });
    }
}
