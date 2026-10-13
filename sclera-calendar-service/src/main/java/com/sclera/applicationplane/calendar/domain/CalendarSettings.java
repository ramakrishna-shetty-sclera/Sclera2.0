package com.sclera.applicationplane.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Date/time rules for one property (property_id NULL = the org-wide default).
 * Persisted in the tenant's schema (schema-per-tenant); org_id is a
 * defense-in-depth cross-check only.
 */
@Entity
@Table(name = "calendar_settings")
public class CalendarSettings {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "property_id")
    private UUID propertyId;

    /** IANA zone id, validated against ZoneId.of at the API boundary. */
    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(name = "use_24_hour_clock", nullable = false)
    private boolean use24HourClock = true;

    @Column(name = "date_format_pattern", nullable = false, length = 32)
    private String dateFormatPattern = "yyyy-MM-dd";

    @Enumerated(EnumType.STRING)
    @Column(name = "first_day_of_week", nullable = false, length = 9)
    private DayOfWeek firstDayOfWeek = DayOfWeek.MONDAY;

    @Enumerated(EnumType.STRING)
    @Column(name = "week_numbering", nullable = false, length = 8)
    private WeekNumbering weekNumbering = WeekNumbering.ISO;

    /** Reserved for non-Gregorian systems; only ISO8601 is implemented. */
    @Column(name = "calendar_system", nullable = false, length = 16)
    private String calendarSystem = "ISO8601";

    @Column(name = "fiscal_year_start_month", nullable = false)
    private int fiscalYearStartMonth = 1;

    @Column(name = "fiscal_year_start_day", nullable = false)
    private int fiscalYearStartDay = 1;

    /** Wall-clock shifts per weekday; missing/empty day = non-working. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "working_hours", nullable = false, columnDefinition = "jsonb")
    private Map<DayOfWeek, List<ShiftDef>> workingHours;

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
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public boolean isUse24HourClock() { return use24HourClock; }
    public void setUse24HourClock(boolean use24HourClock) { this.use24HourClock = use24HourClock; }
    public String getDateFormatPattern() { return dateFormatPattern; }
    public void setDateFormatPattern(String dateFormatPattern) { this.dateFormatPattern = dateFormatPattern; }
    public DayOfWeek getFirstDayOfWeek() { return firstDayOfWeek; }
    public void setFirstDayOfWeek(DayOfWeek firstDayOfWeek) { this.firstDayOfWeek = firstDayOfWeek; }
    public WeekNumbering getWeekNumbering() { return weekNumbering; }
    public void setWeekNumbering(WeekNumbering weekNumbering) { this.weekNumbering = weekNumbering; }
    public String getCalendarSystem() { return calendarSystem; }
    public void setCalendarSystem(String calendarSystem) { this.calendarSystem = calendarSystem; }
    public int getFiscalYearStartMonth() { return fiscalYearStartMonth; }
    public void setFiscalYearStartMonth(int fiscalYearStartMonth) { this.fiscalYearStartMonth = fiscalYearStartMonth; }
    public int getFiscalYearStartDay() { return fiscalYearStartDay; }
    public void setFiscalYearStartDay(int fiscalYearStartDay) { this.fiscalYearStartDay = fiscalYearStartDay; }
    public Map<DayOfWeek, List<ShiftDef>> getWorkingHours() { return workingHours; }
    public void setWorkingHours(Map<DayOfWeek, List<ShiftDef>> workingHours) { this.workingHours = workingHours; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
