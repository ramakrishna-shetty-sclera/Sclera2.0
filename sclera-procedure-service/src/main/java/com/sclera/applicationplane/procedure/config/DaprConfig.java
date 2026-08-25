package com.sclera.applicationplane.procedure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclera.controlplane.common.dapr.DaprInvocationHelper;
import io.dapr.client.DaprClient;
import io.dapr.client.DaprClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dapr wiring: sclera-common ships DaprInvocationHelper as a plain class (no
 * auto-configuration registers it), so the service defines both the DaprClient
 * and the helper bean. ConditionalOnMissingBean keeps this compatible with a
 * future sclera-common that auto-configures them.
 */
@Configuration
public class DaprConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(DaprClient.class)
    public DaprClient daprClient() {
        return new DaprClientBuilder().build();
    }

    @Bean
    @ConditionalOnMissingBean(DaprInvocationHelper.class)
    public DaprInvocationHelper daprInvocationHelper(
            DaprClient daprClient,
            ObjectMapper objectMapper,
            @Value("${sclera.event-listener.signing-secret:}") String signingSecret) {
        return new DaprInvocationHelper(daprClient, objectMapper, signingSecret);
    }
}
