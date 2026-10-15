package com.sclera.applicationplane.procedure.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wiring for AI question generation. Everything in the ai package is
 * {@code @ConditionalOnProperty(sclera.ai.enabled)} — with the flag off, no
 * bean exists and the /api/v1/ai endpoints are plain 404s.
 *
 * Generation jobs run on their own small executor rather than @Async: the job
 * row must be committed before the worker starts (it re-reads it in a new
 * transaction, on a tenant schema it resolves from an explicitly passed org),
 * and a dedicated bean lets tests swap in a synchronous executor.
 */
@Configuration
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    public static final String EXECUTOR = "aiTaskExecutor";

    @Bean(EXECUTOR)
    @ConditionalOnMissingBean(name = EXECUTOR)
    public TaskExecutor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ai-gen-");
        // LLM calls are long and the work is IO-bound; a small pool bounds how
        // many generations one instance runs at once.
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.initialize();
        return executor;
    }
}
