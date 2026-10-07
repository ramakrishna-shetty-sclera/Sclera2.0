package com.sclera.applicationplane.procedure.client.vocabulary;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sclera.external.vocabulary.*}. The app-id is configuration and not a
 * constant, unlike the one hard-coded in the inspection service's client,
 * because pointing at a different service is the whole cutover.
 */
@ConfigurationProperties(prefix = "sclera.external.vocabulary")
public class VocabularyProperties {

    /** Dapr app-id of whatever serves the vocabulary. */
    private String appId = "sclera-helper-service";

    /**
     * How long an organization's vocabulary is kept before it is read again.
     * The lists change rarely, and this takes the remote call off almost every
     * publish; the cost is that a key just added may be refused for up to this
     * long. Zero turns the cache off.
     */
    private long cacheTtlSeconds = 90;

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public long getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(long cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
}