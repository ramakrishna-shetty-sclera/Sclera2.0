package com.sclera.applicationplane.procedure.event;

import com.sclera.applicationplane.procedure.domain.ProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplateVersion;
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
 * Publishes template lifecycle events — after the surrounding transaction
 * commits, not inside it. {@link #publish} only raises a Spring application
 * event; {@link #onCommit} is what actually reaches Kafka, and runs only
 * once the database change that triggered it is durably committed.
 *
 * <p>This closes the dual-write race the inline version of this class had:
 * a consumer could see the event before the row it describes was visible,
 * or ever existed at all if the surrounding transaction later rolled back.
 * It is a <b>scoped</b> fix — after-commit, not before-commit — not a full
 * transactional outbox (a durable table plus a relay process). It does not
 * guarantee delivery if Kafka itself is unreachable at commit time; that
 * gap is judged acceptable for now rather than solved here.
 */
@Component
public class TemplateEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TemplateEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ApplicationEventPublisher applicationEvents;

    public TemplateEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                  ApplicationEventPublisher applicationEvents) {
        this.kafkaTemplate = kafkaTemplate;
        this.applicationEvents = applicationEvents;
    }

    /** {@code version} may be null for an ARCHIVED event on a never-published template. */
    public void publish(ProcedureTemplate template, ProcedureTemplateVersion version,
                        ProcedureTemplateEvent.EventType type) {
        applicationEvents.publishEvent(new ProcedureTemplateEvent(
                UUID.randomUUID(),
                type,
                template.getId(),
                template.getOrgId(),
                template.getName(),
                version == null ? null : version.getId(),
                version == null ? null : version.getVersionNo(),
                version == null ? null : version.getDefinitionHash(),
                OffsetDateTime.now()));
    }

    /**
     * {@code fallbackExecution = true}: if this is ever called with no
     * transaction active — which should never happen, since every real
     * caller is {@code @Transactional} — the event still reaches Kafka
     * immediately rather than being silently discarded, which is Spring's
     * default for a transactional listener with no transaction to wait for.
     * A dropped lifecycle event with no error and no log line is a far
     * worse failure mode than one published a little early.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommit(ProcedureTemplateEvent event) {
        // Key by templateId so all events for one template stay ordered in a partition
        kafkaTemplate.send(ProcedureTemplateEvent.TOPIC, event.templateId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish template event {} for template {}: {}",
                                event.eventType(), event.templateId(), ex.getMessage(), ex);
                    } else {
                        log.info("Published template event {} for template {} v{}",
                                event.eventType(), event.templateId(), event.versionNo());
                    }
                });
    }
}
