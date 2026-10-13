package com.sclera.applicationplane.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Feature opt-in: which calendar layers a feature code respects, and which
 * downtime types / holiday categories affect it (CSV, empty = all). A feature
 * with no row gets the implicit default: respect everything.
 */
@Entity
@Table(name = "feature_calendar_policy")
public class FeatureCalendarPolicy {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "feature_code", nullable = false, length = 100)
    private String featureCode;

    @Column(name = "property_id")
    private UUID propertyId;

    @Column(name = "respect_working_hours", nullable = false)
    private boolean respectWorkingHours = true;

    @Column(name = "respect_downtime", nullable = false)
    private boolean respectDowntime = true;

    @Column(name = "respect_holidays", nullable = false)
    private boolean respectHolidays = true;

    @Column(name = "downtime_types", length = 200)
    private String downtimeTypes;

    @Column(name = "holiday_categories", length = 200)
    private String holidayCategories;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static Set<String> csvToSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public String getFeatureCode() { return featureCode; }
    public void setFeatureCode(String featureCode) { this.featureCode = featureCode; }
    public UUID getPropertyId() { return propertyId; }
    public void setPropertyId(UUID propertyId) { this.propertyId = propertyId; }
    public boolean isRespectWorkingHours() { return respectWorkingHours; }
    public void setRespectWorkingHours(boolean respectWorkingHours) { this.respectWorkingHours = respectWorkingHours; }
    public boolean isRespectDowntime() { return respectDowntime; }
    public void setRespectDowntime(boolean respectDowntime) { this.respectDowntime = respectDowntime; }
    public boolean isRespectHolidays() { return respectHolidays; }
    public void setRespectHolidays(boolean respectHolidays) { this.respectHolidays = respectHolidays; }
    public String getDowntimeTypes() { return downtimeTypes; }
    public void setDowntimeTypes(String downtimeTypes) { this.downtimeTypes = downtimeTypes; }
    public String getHolidayCategories() { return holidayCategories; }
    public void setHolidayCategories(String holidayCategories) { this.holidayCategories = holidayCategories; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
