package com.sclera.applicationplane.calendar.service;

import com.sclera.applicationplane.calendar.domain.WeekNumbering;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WeekAndFiscalYearTest {

    private final CalendarSettingsService service =
            new CalendarSettingsService(null, null, null, null);

    private static EffectiveSettings settings(WeekNumbering numbering, DayOfWeek firstDow,
                                              int fyMonth, int fyDay) {
        return new EffectiveSettings(UUID.randomUUID(), EffectiveSettings.SOURCE_BUILT_IN,
                ZoneId.of("UTC"), true, "yyyy-MM-dd", firstDow, numbering, fyMonth, fyDay, Map.of());
    }

    @Test
    void isoWeekNumbering() {
        EffectiveSettings iso = settings(WeekNumbering.ISO, DayOfWeek.MONDAY, 1, 1);
        // 2027-01-01 is a Friday — ISO week 53 of 2026
        assertThat(service.weekNumber(LocalDate.of(2027, 1, 1), iso)).isEqualTo(53);
        assertThat(service.weekNumber(LocalDate.of(2027, 1, 4), iso)).isEqualTo(1);
    }

    @Test
    void usWeekNumbering() {
        EffectiveSettings us = settings(WeekNumbering.US, DayOfWeek.SUNDAY, 1, 1);
        // US convention: week 1 contains Jan 1
        assertThat(service.weekNumber(LocalDate.of(2027, 1, 1), us)).isEqualTo(1);
    }

    @Test
    void fiscalYearStartingApril() {
        EffectiveSettings fy = settings(WeekNumbering.ISO, DayOfWeek.MONDAY, 4, 1);
        assertThat(service.fiscalYear(LocalDate.of(2026, 3, 31), fy)).isEqualTo(2025);
        assertThat(service.fiscalYear(LocalDate.of(2026, 4, 1), fy)).isEqualTo(2026);
        assertThat(service.fiscalYear(LocalDate.of(2026, 12, 31), fy)).isEqualTo(2026);
    }

    @Test
    void calendarFiscalYearMatchesCalendarYear() {
        EffectiveSettings fy = settings(WeekNumbering.ISO, DayOfWeek.MONDAY, 1, 1);
        assertThat(service.fiscalYear(LocalDate.of(2026, 1, 1), fy)).isEqualTo(2026);
        assertThat(service.fiscalYear(LocalDate.of(2026, 12, 31), fy)).isEqualTo(2026);
    }
}
