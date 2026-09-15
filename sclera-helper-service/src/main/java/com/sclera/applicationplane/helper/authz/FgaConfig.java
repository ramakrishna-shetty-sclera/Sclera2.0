package com.sclera.applicationplane.helper.authz;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.errors.FgaInvalidParameterException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FgaProperties.class)
public class FgaConfig {

    /**
     * Store id is NOT set here: it is resolved lazily by FgaAuthorizationService
     * so the service still boots when OpenFGA is temporarily down.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sclera.fga", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OpenFgaClient openFgaClient(FgaProperties properties) throws FgaInvalidParameterException {
        return new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()));
    }
}

