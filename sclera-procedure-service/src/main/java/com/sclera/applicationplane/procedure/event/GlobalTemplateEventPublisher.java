package com.sclera.applicationplane.procedure.event;

import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Publishes global template lifecycle events — after the surrounding
 * transaction commits, not inside it. Same scoped dual-write fix as
 * {@link TemplateEventPublisher}; see its javadoc for the full reasoning.
 */
@Component
public class GlobalTemplateEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GlobalTemplateEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ApplicationEventPublisher applicationEvents;

    public GlobalTemplateEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                        ApplicationEventPublisher applicationEvents) {
        this.kafkaTemplate = kafkaTemplate;
        this.applicationEvents = applicationEvents;
    }

    public void publish(GlobalProcedureTemplateVersion version, GlobalTemplateEvent.EventType type) {
        applicationEvents.publishEvent(new GlobalTemplateEvent(
                UUID.randomUUID(),
                type,
                version.getGlobalTemplateId(),
                version.getId(),
                version.getVersionNo(),
                OffsetDateTime.now()));
    }

    /** {@code fallbackExecution = true} for the same reason {@link TemplateEventPublisher#onCommit} sets it. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommit(GlobalTemplateEvent event) {
        // Key by globalTemplateId so all events for one template stay ordered in a partition
        kafkaTemplate.send(GlobalTemplateEvent.TOPIC, event.globalTemplateId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish global template event {} for template {}: {}",
                                event.eventType(), event.globalTemplateId(), ex.getMessage(), ex);
                    } else {
                        log.info("Published global template event {} for template {} v{}",
                                event.eventType(), event.globalTemplateId(), event.versionNo());
                    }
                });
    }
}
