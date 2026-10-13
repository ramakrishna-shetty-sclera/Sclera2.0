package com.sclera.applicationplane.calendar.service;

import com.sclera.applicationplane.calendar.domain.ShiftDef;
import com.sclera.applicationplane.calendar.domain.WeekNumbering;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Settings after resolution: the property's row, else the org default row,
 * else the built-in defaults (UTC, Mon–Fri 09:00–17:00).
 */
public record EffectiveSettings(
        UUID propertyId,
        String source,              // PROPERTY | ORG_DEFAULT | BUILT_IN
        ZoneId zone,
        boolean use24HourClock,
        String dateFormatPattern,
        DayOfWeek firstDayOfWeek,
        WeekNumbering weekNumbering,
        int fiscalYearStartMonth,
        int fiscalYearStartDay,
        Map<DayOfWeek, List<ShiftDef>> workingHours) {

    public static final String SOURCE_PROPERTY = "PROPERTY";
    public static final String SOURCE_ORG_DEFAULT = "ORG_DEFAULT";
    public static final String SOURCE_BUILT_IN = "BUILT_IN";

    public static EffectiveSettings builtIn(UUID propertyId) {
        ShiftDef nineToFive = new ShiftDef("09:00", "17:00");
        Map<DayOfWeek, List<ShiftDef>> hours = Map.of(
                DayOfWeek.MONDAY, List.of(nineToFive),
                DayOfWeek.TUESDAY, List.of(nineToFive),
                DayOfWeek.WEDNESDAY, List.of(nineToFive),
                DayOfWeek.THURSDAY, List.of(nineToFive),
                DayOfWeek.FRIDAY, List.of(nineToFive));
        return new EffectiveSettings(propertyId, SOURCE_BUILT_IN, ZoneId.of("UTC"), true,
                "yyyy-MM-dd", DayOfWeek.MONDAY, WeekNumbering.ISO, 1, 1, hours);
    }
}
