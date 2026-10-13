package com.sclera.applicationplane.calendar.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Calendar wiring. The Clock bean is the one "now" source for the service —
 * injected instead of calling OffsetDateTime.now() inline so tests can pin it.
 */
@Configuration
@EnableConfigurationProperties(CalendarProperties.class)
public class CalendarConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
