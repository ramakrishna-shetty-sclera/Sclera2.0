package com.sclera.applicationplane.calendar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Calendar tuning knobs (sclera.calendar.* in application.yml). */
@ConfigurationProperties(prefix = "sclera.calendar")
public class CalendarProperties {

    /** TTL of the per-instance read caches; writes invalidate locally at once. */
    private long cacheTtlSeconds = 60;

    /** How far getNextAvailableSlot scans before giving up. */
    private int nextSlotHorizonDays = 366;

    /** Max span of a getUnavailableRanges query. */
    private int maxRangeDays = 93;

    public long getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(long cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
    public int getNextSlotHorizonDays() { return nextSlotHorizonDays; }
    public void setNextSlotHorizonDays(int nextSlotHorizonDays) { this.nextSlotHorizonDays = nextSlotHorizonDays; }
    public int getMaxRangeDays() { return maxRangeDays; }
    public void setMaxRangeDays(int maxRangeDays) { this.maxRangeDays = maxRangeDays; }
}
