package com.sclera.applicationplane.calendar.engine;

import com.sclera.applicationplane.calendar.domain.Holiday;
import com.sclera.applicationplane.calendar.domain.HolidayOverride;
import com.sclera.applicationplane.calendar.engine.HolidayResolver.ResolvedHoliday;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HolidayResolverTest {

    private static Holiday fixed(String name, int month, int day) {
        Holiday h = new Holiday();
        h.setId(UUID.randomUUID());
        h.setName(name);
        h.setCategory("PUBLIC");
        h.setDateType(Holiday.DateType.FIXED);
        h.setMonth(month);
        h.setDayOfMonth(day);
        return h;
    }

    private static Holiday floating(String name, int month, int ordinal, DayOfWeek dow) {
        Holiday h = new Holiday();
        h.setId(UUID.randomUUID());
        h.setName(name);
        h.setCategory("PUBLIC");
        h.setDateType(Holiday.DateType.FLOATING);
        h.setMonth(month);
        h.setWeekOrdinal(ordinal);
        h.setDayOfWeek(dow);
        return h;
    }

    @Test
    void fixedHolidayEveryYearInRange() {
        List<ResolvedHoliday> resolved = HolidayResolver.resolve(
                List.of(fixed("Christmas", 12, 25)), List.of(),
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(resolved).extracting(ResolvedHoliday::date)
                .containsExactly(LocalDate.of(2025, 12, 25), LocalDate.of(2026, 12, 25));
    }

    @Test
    void floatingThirdMondayOfJanuary() {
        List<ResolvedHoliday> resolved = HolidayResolver.resolve(
                List.of(floating("MLK Day", 1, 3, DayOfWeek.MONDAY)), List.of(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(resolved).extracting(ResolvedHoliday::date)
                .containsExactly(LocalDate.of(2026, 1, 19));
    }

    @Test
    void floatingLastMondayOfMay() {
        List<ResolvedHoliday> resolved = HolidayResolver.resolve(
                List.of(floating("Memorial Day", 5, -1, DayOfWeek.MONDAY)), List.of(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(resolved).extracting(ResolvedHoliday::date)
                .containsExactly(LocalDate.of(2026, 5, 25));
    }

    @Test
    void leapDayObservedFeb28InNonLeapYears() {
        List<ResolvedHoliday> resolved = HolidayResolver.resolve(
                List.of(fixed("Leap Fest", 2, 29)), List.of(),
                LocalDate.of(2024, 1, 1), LocalDate.of(2025, 12, 31));
        assertThat(resolved).extracting(ResolvedHoliday::date)
                .containsExactly(LocalDate.of(2024, 2, 29), LocalDate.of(2025, 2, 28));
    }

    @Test
    void disableOverrideForOneYearOnly() {
        Holiday christmas = fixed("Christmas", 12, 25);
        HolidayOverride disable2025 = new HolidayOverride();
        disable2025.setId(UUID.randomUUID());
        disable2025.setHolidayId(christmas.getId());
        disable2025.setAction(HolidayOverride.Action.DISABLE);
        disable2025.setYear(2025);

        List<ResolvedHoliday> resolved = HolidayResolver.resolve(
                List.of(christmas), List.of(disable2025),
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(resolved).extracting(ResolvedHoliday::date)
                .containsExactly(LocalDate.of(2026, 12, 25));
    }

    @Test
    void disableOverrideForEveryYear() {
        Holiday christmas = fixed("Christmas", 12, 25);
        HolidayOverride disableAll = new HolidayOverride();
        disableAll.setId(UUID.randomUUID());
        disableAll.setHolidayId(christmas.getId());
        disableAll.setAction(HolidayOverride.Action.DISABLE);

        assertThat(HolidayResolver.resolve(
                List.of(christmas), List.of(disableAll),
                LocalDate.of(2024, 1, 1), LocalDate.of(2027, 12, 31))).isEmpty();
    }

    @Test
    void moveOverrideRelocatesTheObservedDate() {
        Holiday holiday = fixed("Founders Day", 7, 4);
        HolidayOverride move = new HolidayOverride();
        move.setId(UUID.randomUUID());
        move.setHolidayId(holiday.getId());
        move.setAction(HolidayOverride.Action.MOVE);
        move.setYear(2026);
        move.setMovedTo(LocalDate.of(2026, 7, 6));

        List<ResolvedHoliday> resolved = HolidayResolver.resolve(
                List.of(holiday), List.of(move),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(resolved).extracting(ResolvedHoliday::date)
                .containsExactly(LocalDate.of(2026, 7, 6));
    }

    @Test
    void oneOffAppearsOnlyInItsYear() {
        Holiday h = new Holiday();
        h.setId(UUID.randomUUID());
        h.setName("Company Offsite");
        h.setCategory("COMPANY");
        h.setDateType(Holiday.DateType.ONE_OFF);
        h.setOnDate(LocalDate.of(2026, 3, 13));

        assertThat(HolidayResolver.resolve(List.of(h), List.of(),
                LocalDate.of(2025, 1, 1), LocalDate.of(2027, 12, 31)))
                .extracting(ResolvedHoliday::date)
                .containsExactly(LocalDate.of(2026, 3, 13));
    }
}
