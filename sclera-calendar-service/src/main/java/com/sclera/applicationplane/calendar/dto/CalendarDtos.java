package com.sclera.applicationplane.calendar.dto;

import com.sclera.applicationplane.calendar.domain.DowntimeWindow;
import com.sclera.applicationplane.calendar.domain.Holiday;
import com.sclera.applicationplane.calendar.domain.HolidayOverride;
import com.sclera.applicationplane.calendar.domain.ShiftDef;
import com.sclera.applicationplane.calendar.domain.WeekNumbering;
import com.sclera.applicationplane.calendar.engine.AvailabilityCalculator;
import com.sclera.applicationplane.calendar.engine.RecurrenceRule;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response records for the calendar API (envelope-wrapped by sclera-common). */
public final class CalendarDtos {

    private CalendarDtos() {}

    // ── settings ────────────────────────────────────────────────────────────

    public record SettingsRequest(
            UUID propertyId,
            @NotBlank @Size(max = 64) String timezone,
            Boolean use24HourClock,
            @Size(max = 32) String dateFormatPattern,
            DayOfWeek firstDayOfWeek,
            WeekNumbering weekNumbering,
            @Min(1) @Max(12) Integer fiscalYearStartMonth,
            @Min(1) @Max(31) Integer fiscalYearStartDay,
            @NotNull Map<DayOfWeek, List<ShiftDef>> workingHours
    ) {}

    public record SettingsResponse(
            UUID id, UUID orgId, UUID propertyId, String timezone, boolean use24HourClock,
            String dateFormatPattern, DayOfWeek firstDayOfWeek, WeekNumbering weekNumbering,
            String calendarSystem, int fiscalYearStartMonth, int fiscalYearStartDay,
            Map<DayOfWeek, List<ShiftDef>> workingHours,
            UUID createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {}

    /** source = PROPERTY | ORG_DEFAULT | BUILT_IN. dateInfo present when ?date= was given. */
    public record EffectiveSettingsResponse(
            UUID propertyId, String source, String timezone, boolean use24HourClock,
            String dateFormatPattern, DayOfWeek firstDayOfWeek, WeekNumbering weekNumbering,
            int fiscalYearStartMonth, int fiscalYearStartDay,
            Map<DayOfWeek, List<ShiftDef>> workingHours,
            DateInfo dateInfo
    ) {}

    public record DateInfo(LocalDate date, int weekNumber, int fiscalYear,
                           boolean workingDay, boolean holiday, String holidayName) {}

    // ── downtime ────────────────────────────────────────────────────────────

    public record DowntimeRequest(
            UUID propertyId,
            DowntimeWindow.ScopeType scopeType,
            UUID scopeId,
            @NotNull DowntimeWindow.Type downtimeType,
            @Size(max = 500) String reason,
            @NotNull DowntimeWindow.Kind kind,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            RecurrenceRule recurrence,
            LocalDate seriesStart,
            LocalTime localStartTime,
            @Min(1) Integer durationMinutes,
            Boolean active
    ) {}

    public record DowntimeResponse(
            UUID id, UUID orgId, UUID propertyId, DowntimeWindow.ScopeType scopeType, UUID scopeId,
            DowntimeWindow.Type downtimeType, String reason, DowntimeWindow.Kind kind,
            OffsetDateTime startAt, OffsetDateTime endAt,
            RecurrenceRule recurrence, LocalDate seriesStart, LocalTime localStartTime,
            Integer durationMinutes, boolean active,
            UUID createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {}

    // ── holidays ────────────────────────────────────────────────────────────

    public record HolidayRequest(
            UUID propertyId,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 32) String category,
            @NotNull Holiday.DateType dateType,
            @Min(1) @Max(12) Integer month,
            @Min(1) @Max(31) Integer dayOfMonth,
            Integer weekOrdinal,
            DayOfWeek dayOfWeek,
            LocalDate onDate,
            Boolean enabled
    ) {}

    public record HolidayResponse(
            UUID id, UUID orgId, UUID propertyId, String name, String category,
            Holiday.DateType dateType, Integer month, Integer dayOfMonth,
            Integer weekOrdinal, DayOfWeek dayOfWeek, LocalDate onDate,
            String source, boolean enabled,
            UUID createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {}

    public record ResolvedHolidayResponse(LocalDate date, UUID holidayId, String name, String category) {}

    public record HolidayImportRequest(@NotBlank @Size(max = 16) String region, UUID propertyId) {}

    public record OverrideRequest(
            @NotNull UUID propertyId,
            @NotNull HolidayOverride.Action action,
            Integer year,
            LocalDate movedTo
    ) {}

    public record OverrideResponse(
            UUID id, UUID orgId, UUID propertyId, UUID holidayId,
            HolidayOverride.Action action, Integer year, LocalDate movedTo,
            UUID createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {}

    // ── feature policies ────────────────────────────────────────────────────

    public record PolicyRequest(
            @NotBlank @Size(max = 100) String featureCode,
            UUID propertyId,
            Boolean respectWorkingHours,
            Boolean respectDowntime,
            Boolean respectHolidays,
            List<DowntimeWindow.Type> downtimeTypes,
            List<String> holidayCategories
    ) {}

    public record PolicyResponse(
            UUID id, UUID orgId, String featureCode, UUID propertyId,
            boolean respectWorkingHours, boolean respectDowntime, boolean respectHolidays,
            List<String> downtimeTypes, List<String> holidayCategories,
            UUID createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt
    ) {}

    // ── availability ────────────────────────────────────────────────────────

    public record AvailabilityResponse(
            OffsetDateTime at, boolean available,
            AvailabilityCalculator.Cause cause, String detail
    ) {}

    public record NextSlotResponse(OffsetDateTime start, OffsetDateTime end) {}

    public record UnavailableRangeResponse(
            OffsetDateTime start, OffsetDateTime end,
            AvailabilityCalculator.Cause cause, String detail
    ) {}

    public record ResolvedShift(OffsetDateTime start, OffsetDateTime end) {}

    public record WorkingHoursResponse(
            LocalDate date, String timezone, boolean workingDay,
            boolean holiday, String holidayName, List<ResolvedShift> shifts
    ) {}
}
