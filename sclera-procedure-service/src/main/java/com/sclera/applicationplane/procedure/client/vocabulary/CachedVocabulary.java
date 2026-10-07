package com.sclera.applicationplane.procedure.client.vocabulary;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * The vocabulary a caller asks for, read through to the {@link VocabularyClient}
 * at most once per organization per TTL. This is what publish calls, so that
 * the remote read comes off almost every publish: the lists change rarely.
 *
 * <p><b>A failed read is never cached.</b> Caffeine's {@code get(key, loader)}
 * stores nothing when the loader throws, so the next publish tries again
 * instead of replaying an outage for a minute and a half after it ended. The
 * exception reaches the caller as it was, a
 * {@link VocabularyUnavailableException}.
 *
 * <p>Concurrent first reads for one organization share a single call.
 *
 * <p>A plain Caffeine field, as in {@code FgaAuthorizationService}, rather than
 * Spring's {@code @Cacheable}: the common cache manager has a fixed list of
 * cache names and does not create one on demand.
 *
 * <p>Only the vocabulary is cached, never an answer derived from it, so a
 * retired key is seen as retired as soon as the entry expires.
 */
@Component
public class CachedVocabulary {

    private static final long MAX_ORGANIZATIONS = 1_000;

    private final VocabularyClient client;
    /** Null when the TTL is zero: every call reads through. */
    private final Cache<UUID, Vocabulary> cache;

    @Autowired
    public CachedVocabulary(VocabularyClient client, VocabularyProperties properties) {
        this(client, properties, Ticker.systemTicker());
    }

    /** The ticker is a parameter so a test can move time without sleeping. */
    CachedVocabulary(VocabularyClient client, VocabularyProperties properties, Ticker ticker) {
        this.client = client;
        long ttl = properties.getCacheTtlSeconds();
        this.cache = ttl > 0
                ? Caffeine.newBuilder()
                        .maximumSize(MAX_ORGANIZATIONS)
                        .expireAfterWrite(Duration.ofSeconds(ttl))
                        .ticker(ticker)
                        .build()
                : null;
    }

    /**
     * The organization's whole vocabulary, retired keys included.
     *
     * @throws VocabularyUnavailableException when it cannot be read
     */
    public Vocabulary forOrg(UUID orgId) {
        return cache == null ? client.fetch(orgId) : cache.get(orgId, client::fetch);
    }
}