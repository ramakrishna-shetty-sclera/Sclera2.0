package com.sclera.applicationplane.procedure.config;

import com.sclera.applicationplane.procedure.event.ProcedureTemplateEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic templateEventsTopic() {
        return TopicBuilder.name(ProcedureTemplateEvent.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
