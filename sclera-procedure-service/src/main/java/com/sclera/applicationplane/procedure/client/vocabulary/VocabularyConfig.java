package com.sclera.applicationplane.procedure.client.vocabulary;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link VocabularyProperties}; nothing else to wire, the client is a component. */
@Configuration
@EnableConfigurationProperties(VocabularyProperties.class)
public class VocabularyConfig {
}