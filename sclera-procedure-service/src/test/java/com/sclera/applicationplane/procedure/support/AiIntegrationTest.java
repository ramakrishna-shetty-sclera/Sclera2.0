package com.sclera.applicationplane.procedure.support;

import com.sclera.applicationplane.procedure.ai.LlmClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Base for AI-feature integration tests: the feature flag is on, and the LLM
 * is a mock — tests script its structured output and assert what the pipeline
 * does with it. The background executor is the real thread pool, so tests
 * poll for job/document completion like a client would.
 */
@TestPropertySource(properties = "sclera.ai.enabled=true")
public abstract class AiIntegrationTest extends PostgresIntegrationTest {

    @MockBean
    protected LlmClient llm;

    /** Polls until {@code done}, failing after 20s — generous for a mocked LLM. */
    protected <T> T await(Supplier<T> poll, Predicate<T> done) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        T last = null;
        while (Instant.now().isBefore(deadline)) {
            last = poll.get();
            if (done.test(last)) {
                return last;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Condition not reached in time; last state: " + last);
    }
}
