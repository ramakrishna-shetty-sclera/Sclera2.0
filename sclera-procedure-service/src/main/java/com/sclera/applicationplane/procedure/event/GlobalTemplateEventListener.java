package com.sclera.applicationplane.procedure.event;

import com.sclera.applicationplane.procedure.service.GlobalQuestionIndexer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Hears that a Sclera-wide template has a new published version and has its
 * questions indexed for the question bank.
 *
 * <p>The event is the only thing connecting this to whoever publishes global
 * templates, which is what lets the two be built independently.
 *
 * <p>{@code autoStartup} is a property so that tests, which run without a
 * broker, can switch the consumer off instead of having it retry a connection.
 */
@Component
public class GlobalTemplateEventListener {

    private final GlobalQuestionIndexer indexer;

    public GlobalTemplateEventListener(GlobalQuestionIndexer indexer) {
        this.indexer = indexer;
    }

    @KafkaListener(topics = GlobalTemplateEvent.TOPIC,
            autoStartup = "${sclera.library.index-listener.enabled:true}")
    public void onGlobalTemplateEvent(GlobalTemplateEvent event) {
        if (event.eventType() == GlobalTemplateEvent.EventType.PUBLISHED) {
            indexer.indexPublished(event);
        }
    }
}
