package com.sclera.applicationplane.procedure.event;

import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class GlobalTemplateEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GlobalTemplateEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public GlobalTemplateEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(GlobalProcedureTemplateVersion version, GlobalTemplateEvent.EventType type) {
        GlobalTemplateEvent event = new GlobalTemplateEvent(
                UUID.randomUUID(),
                type,
                version.getGlobalTemplateId(),
                version.getId(),
                version.getVersionNo(),
                OffsetDateTime.now());

        // Key by globalTemplateId so all events for one template stay ordered in a partition
        kafkaTemplate.send(GlobalTemplateEvent.TOPIC, version.getGlobalTemplateId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish global template event {} for template {}: {}",
                                type, version.getGlobalTemplateId(), ex.getMessage(), ex);
                    } else {
                        log.info("Published global template event {} for template {} v{}",
                                type, version.getGlobalTemplateId(), event.versionNo());
                    }
                });
    }
}
