package com.sclera.applicationplane.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A holiday definition: FIXED (month/day each year, Feb 29 observed Feb 28 in
 * non-leap years), FLOATING ("3rd Monday of January", ordinal -1 = last) or
 * ONE_OFF (a single concrete date). property_id NULL = org-wide; per-property
 * adjustments go through {@link HolidayOverride}.
 */
@Entity
@Table(name = "holiday")
public class Holiday {

    public enum DateType { FIXED, FLOATING, ONE_OFF }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "property_id")
    private UUID propertyId;

    @Column(nullable = false, length = 200)
    private String name;

    /** Free-form grouping features can opt in/out of: PUBLIC, COMPANY, RELIGIOUS… */
    @Column(nullable = false, length = 32)
    private String category = "PUBLIC";

    @Enumerated(EnumType.STRING)
    @Column(name = "date_type", nullable = false, length = 12)
    private DateType dateType;

    @Column(name = "month")
    private Integer month;

    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @Column(name = "week_ordinal")
    private Integer weekOrdinal;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 9)
    private DayOfWeek dayOfWeek;

    @Column(name = "on_date")
    private LocalDate onDate;

    /** MANUAL or the region pack it was imported from (US, GB, IN…). */
    @Column(nullable = false, length = 16)
    private String source = "MANUAL";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public UUID getPropertyId() { return propertyId; }
    public void setPropertyId(UUID propertyId) { this.propertyId = propertyId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public DateType getDateType() { return dateType; }
    public void setDateType(DateType dateType) { this.dateType = dateType; }
    public Integer getMonth() { return month; }
    public void setMonth(Integer month) { this.month = month; }
    public Integer getDayOfMonth() { return dayOfMonth; }
    public void setDayOfMonth(Integer dayOfMonth) { this.dayOfMonth = dayOfMonth; }
    public Integer getWeekOrdinal() { return weekOrdinal; }
    public void setWeekOrdinal(Integer weekOrdinal) { this.weekOrdinal = weekOrdinal; }
    public DayOfWeek getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(DayOfWeek dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    public LocalDate getOnDate() { return onDate; }
    public void setOnDate(LocalDate onDate) { this.onDate = onDate; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
