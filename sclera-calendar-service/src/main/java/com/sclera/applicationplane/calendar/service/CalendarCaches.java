package com.sclera.applicationplane.calendar.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sclera.applicationplane.calendar.config.CalendarProperties;
import com.sclera.applicationplane.calendar.domain.DowntimeWindow;
import com.sclera.applicationplane.calendar.domain.Holiday;
import com.sclera.applicationplane.calendar.domain.HolidayOverride;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Per-instance Caffeine read caches (same pattern as the FGA decision cache).
 * Any calendar write invalidates the org's keys locally; other instances
 * converge within the TTL — acceptable staleness for admin-edited config.
 */
@Component
public class CalendarCaches {

    /** Everything availability needs besides settings, loaded per org in one go. */
    public record RuleBundle(List<Holiday> holidays,
                             List<HolidayOverride> overrides,
                             List<DowntimeWindow> downtime) {}

    private final Cache<String, EffectiveSettings> settings;
    private final Cache<UUID, RuleBundle> bundles;

    public CalendarCaches(CalendarProperties properties) {
        Duration ttl = Duration.ofSeconds(Math.max(1, properties.getCacheTtlSeconds()));
        this.settings = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(ttl).build();
        this.bundles = Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(ttl).build();
    }

    public EffectiveSettings settings(UUID orgId, UUID propertyId, Supplier<EffectiveSettings> loader) {
        return settings.get(orgId + "|" + propertyId, k -> loader.get());
    }

    public RuleBundle bundle(UUID orgId, Supplier<RuleBundle> loader) {
        return bundles.get(orgId, k -> loader.get());
    }

    public void invalidateOrg(UUID orgId) {
        bundles.invalidate(orgId);
        String prefix = orgId + "|";
        settings.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }
}
