package com.sclera.applicationplane.procedure.client.vocabulary;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The cache with a fake client and a clock the test moves by hand — no sleeping. */
class CachedVocabularyTest {

    /** Counts reads and can be told to fail. */
    private static final class FakeClient implements VocabularyClient {
        final List<UUID> reads = new ArrayList<>();
        boolean failing;

        @Override
        public Vocabulary fetch(UUID orgId) {
            reads.add(orgId);
            if (failing) {
                throw new VocabularyUnavailableException("The vocabulary service could not be reached", null);
            }
            return new Vocabulary(Map.of(VocabularyKind.ASSET_CLASS,
                    List.of(new VocabularyEntry("EXTINGUISHER", "Fire extinguisher", 1, true))));
        }
    }

    private final FakeClient client = new FakeClient();
    private final AtomicLong nanos = new AtomicLong();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    private CachedVocabulary cacheFor(long ttlSeconds) {
        VocabularyProperties properties = new VocabularyProperties();
        properties.setCacheTtlSeconds(ttlSeconds);
        return new CachedVocabulary(client, properties, nanos::get);
    }

    private void passSeconds(long seconds) {
        nanos.addAndGet(Duration.ofSeconds(seconds).toNanos());
    }

    @Test
    void aSecondReadWithinTheTtlDoesNotCallTheClient() {
        CachedVocabulary cache = cacheFor(90);

        Vocabulary one = cache.forOrg(first);
        passSeconds(89);
        Vocabulary two = cache.forOrg(first);

        assertThat(client.reads).hasSize(1);
        assertThat(two).isSameAs(one);
    }

    @Test
    void afterTheTtlItReadsAgain() {
        CachedVocabulary cache = cacheFor(90);

        cache.forOrg(first);
        passSeconds(91);
        cache.forOrg(first);

        assertThat(client.reads).hasSize(2);
    }

    @Test
    void eachOrganizationHasItsOwnEntry() {
        CachedVocabulary cache = cacheFor(90);

        cache.forOrg(first);
        cache.forOrg(second);
        cache.forOrg(first);

        assertThat(client.reads).containsExactly(first, second);
    }

    @Test
    void aFailedReadIsNotCachedSoTheNextCallTriesAgain() {
        CachedVocabulary cache = cacheFor(90);
        client.failing = true;

        assertThatThrownBy(() -> cache.forOrg(first)).isInstanceOf(VocabularyUnavailableException.class);
        client.failing = false;
        Vocabulary recovered = cache.forOrg(first);

        // Replaying the outage for the whole TTL would refuse every publish for
        // a minute and a half after the helper came back.
        assertThat(client.reads).hasSize(2);
        assertThat(recovered.contains(VocabularyKind.ASSET_CLASS, "EXTINGUISHER")).isTrue();
    }

    @Test
    void theUnavailableExceptionReachesTheCallerAsItWas() {
        CachedVocabulary cache = cacheFor(90);
        client.failing = true;

        assertThatThrownBy(() -> cache.forOrg(first))
                .isInstanceOf(VocabularyUnavailableException.class)
                .hasMessage("The vocabulary service could not be reached");
    }

    @Test
    void aTtlOfZeroTurnsTheCacheOff() {
        CachedVocabulary cache = cacheFor(0);

        cache.forOrg(first);
        cache.forOrg(first);

        assertThat(client.reads).hasSize(2);
    }
}