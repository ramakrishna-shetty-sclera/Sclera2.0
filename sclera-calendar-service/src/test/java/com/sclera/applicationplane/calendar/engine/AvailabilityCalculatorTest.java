package com.sclera.applicationplane.calendar.engine;

import com.sclera.applicationplane.calendar.domain.DowntimeWindow;
import com.sclera.applicationplane.calendar.domain.Holiday;
import com.sclera.applicationplane.calendar.domain.ShiftDef;
import com.sclera.applicationplane.calendar.engine.AvailabilityCalculator.Cause;
import com.sclera.applicationplane.calendar.engine.AvailabilityCalculator.PropertyCalendar;
import com.sclera.applicationplane.calendar.engine.AvailabilityCalculator.Range;
import com.sclera.applicationplane.calendar.engine.AvailabilityCalculator.UnavailableRange;
import com.sclera.applicationplane.calendar.engine.HolidayResolver.ResolvedHoliday;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityCalculatorTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static Map<DayOfWeek, List<ShiftDef>> weekdays9to5() {
        ShiftDef s = new ShiftDef("09:00", "17:00");
        return Map.of(DayOfWeek.MONDAY, List.of(s), DayOfWeek.TUESDAY, List.of(s),
                DayOfWeek.WEDNESDAY, List.of(s), DayOfWeek.THURSDAY, List.of(s),
                DayOfWeek.FRIDAY, List.of(s));
    }

    private static PropertyCalendar calendar(ZoneId zone, Map<DayOfWeek, List<ShiftDef>> hours,
                                             Map<LocalDate, ResolvedHoliday> holidays,
                                             List<DowntimeWindow> downtime) {
        return new PropertyCalendar(zone, hours, true, holidays, downtime);
    }

    private static DowntimeWindow oneOff(Instant start, Instant end) {
        DowntimeWindow w = new DowntimeWindow();
        w.setId(UUID.randomUUID());
        w.setKind(DowntimeWindow.Kind.ONE_OFF);
        w.setDowntimeType(DowntimeWindow.Type.MAINTENANCE);
        w.setStartAt(OffsetDateTime.ofInstant(start, ZoneOffset.UTC));
        w.setEndAt(OffsetDateTime.ofInstant(end, ZoneOffset.UTC));
        w.setActive(true);
        return w;
    }

    private static ResolvedHoliday holiday(LocalDate date, String name) {
        return new ResolvedHoliday(date, UUID.randomUUID(), name, "PUBLIC");
    }

    private static Instant at(ZoneId zone, LocalDate date, String time) {
        return ZonedDateTime.of(date, LocalTime.parse(time), zone).toInstant();
    }

    // ── DST transitions ─────────────────────────────────────────────────────

    @Test
    void springForwardGapMovesShiftStartForward() {
        // America/New_York 2026-03-08: 02:00→03:00 does not exist
        LocalDate dstDay = LocalDate.of(2026, 3, 8);   // a Sunday
        Map<DayOfWeek, List<ShiftDef>> hours = Map.of(
                DayOfWeek.SUNDAY, List.of(new ShiftDef("02:30", "04:00")));
        List<Range> segments = AvailabilityCalculator.workingSegments(
                calendar(NEW_YORK, hours, Map.of(), List.of()), dstDay);
        assertThat(segments).hasSize(1);
        // 02:30 resolves to 03:30 EDT — the shift is 30 real minutes, not 90
        assertThat(Duration.between(segments.get(0).start(), segments.get(0).end()))
                .isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void springForwardDayIs23HoursLong() {
        LocalDate dstDay = LocalDate.of(2026, 3, 8);
        Map<DayOfWeek, List<ShiftDef>> hours = Map.of(
                DayOfWeek.SUNDAY, List.of(new ShiftDef("00:00", "08:00")));
        List<Range> segments = AvailabilityCalculator.workingSegments(
                calendar(NEW_YORK, hours, Map.of(), List.of()), dstDay);
        // 00:00 EST → 08:00 EDT = 7 elapsed hours (the 02:00 hour is skipped)
        assertThat(Duration.between(segments.get(0).start(), segments.get(0).end()))
                .isEqualTo(Duration.ofHours(7));
    }

    @Test
    void fallBackDayIs25HoursLong() {
        // America/New_York 2026-11-01: 01:00–02:00 happens twice
        LocalDate dstDay = LocalDate.of(2026, 11, 1);   // a Sunday
        Map<DayOfWeek, List<ShiftDef>> hours = Map.of(
                DayOfWeek.SUNDAY, List.of(new ShiftDef("00:00", "03:00")));
        List<Range> segments = AvailabilityCalculator.workingSegments(
                calendar(NEW_YORK, hours, Map.of(), List.of()), dstDay);
        // 00:00 EDT → 03:00 EST = 4 elapsed hours (the 01:00 hour repeats)
        assertThat(Duration.between(segments.get(0).start(), segments.get(0).end()))
                .isEqualTo(Duration.ofHours(4));
    }

    // ── cross-midnight shifts ───────────────────────────────────────────────

    @Test
    void crossMidnightShiftCoversEarlySaturday() {
        Map<DayOfWeek, List<ShiftDef>> hours = Map.of(
                DayOfWeek.FRIDAY, List.of(new ShiftDef("22:00", "06:00")));
        PropertyCalendar cal = calendar(UTC, hours, Map.of(), List.of());

        LocalDate friday = LocalDate.of(2026, 1, 9);
        List<Range> segments = AvailabilityCalculator.workingSegments(cal, friday);
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).end()).isEqualTo(at(UTC, friday.plusDays(1), "06:00"));

        // 03:00 Saturday is inside Friday's shift
        assertThat(AvailabilityCalculator.blockingCause(cal, at(UTC, friday.plusDays(1), "03:00")))
                .isEmpty();
        // 07:00 Saturday is not
        assertThat(AvailabilityCalculator.blockingCause(cal, at(UTC, friday.plusDays(1), "07:00")))
                .contains(Cause.NON_WORKING_HOURS);
    }

    @Test
    void multipleShiftsPerDay() {
        Map<DayOfWeek, List<ShiftDef>> hours = Map.of(
                DayOfWeek.MONDAY, List.of(new ShiftDef("06:00", "10:00"), new ShiftDef("14:00", "18:00")));
        PropertyCalendar cal = calendar(UTC, hours, Map.of(), List.of());
        LocalDate monday = LocalDate.of(2026, 1, 5);
        assertThat(AvailabilityCalculator.workingSegments(cal, monday)).hasSize(2);
        assertThat(AvailabilityCalculator.blockingCause(cal, at(UTC, monday, "12:00")))
                .contains(Cause.NON_WORKING_HOURS);
        assertThat(AvailabilityCalculator.blockingCause(cal, at(UTC, monday, "15:00"))).isEmpty();
    }

    // ── holidays & downtime ─────────────────────────────────────────────────

    @Test
    void holidayRemovesTheWorkingDay() {
        LocalDate monday = LocalDate.of(2026, 1, 5);
        PropertyCalendar cal = calendar(UTC, weekdays9to5(),
                Map.of(monday, holiday(monday, "New Year observed")), List.of());
        assertThat(AvailabilityCalculator.workingSegments(cal, monday)).isEmpty();
        assertThat(AvailabilityCalculator.blockingCause(cal, at(UTC, monday, "10:00")))
                .contains(Cause.HOLIDAY);
    }

    @Test
    void overlappingDowntimeWindowsUnion() {
        LocalDate monday = LocalDate.of(2026, 1, 5);
        PropertyCalendar cal = calendar(UTC, weekdays9to5(), Map.of(), List.of(
                oneOff(at(UTC, monday, "10:00"), at(UTC, monday, "12:00")),
                oneOff(at(UTC, monday, "11:00"), at(UTC, monday, "13:00"))));
        List<Range> occurrences = AvailabilityCalculator.downtimeOccurrences(
                cal, at(UTC, monday, "00:00"), at(UTC, monday.plusDays(1), "00:00"));
        assertThat(occurrences).containsExactly(
                new Range(at(UTC, monday, "10:00"), at(UTC, monday, "13:00")));
    }

    @Test
    void recurringDowntimeExpandsInPropertyZone() {
        DowntimeWindow w = new DowntimeWindow();
        w.setId(UUID.randomUUID());
        w.setKind(DowntimeWindow.Kind.RECURRING);
        w.setDowntimeType(DowntimeWindow.Type.MAINTENANCE);
        w.setActive(true);
        RecurrenceRule weeklyMonday = new RecurrenceRule();
        weeklyMonday.setFreq(RecurrenceRule.Freq.WEEKLY);
        weeklyMonday.setByDays(java.util.Set.of(DayOfWeek.MONDAY));
        w.setRecurrence(weeklyMonday);
        w.setSeriesStart(LocalDate.of(2026, 1, 5));
        w.setLocalStartTime(LocalTime.of(9, 0));
        w.setDurationMinutes(120);

        PropertyCalendar cal = calendar(NEW_YORK, weekdays9to5(), Map.of(), List.of(w));
        LocalDate monday = LocalDate.of(2026, 1, 12);
        List<Range> occurrences = AvailabilityCalculator.downtimeOccurrences(
                cal, at(NEW_YORK, monday, "00:00"), at(NEW_YORK, monday.plusDays(1), "00:00"));
        assertThat(occurrences).containsExactly(
                new Range(at(NEW_YORK, monday, "09:00"), at(NEW_YORK, monday, "11:00")));
    }

    @Test
    void unavailableRangesMergeWithDowntimePrecedence() {
        LocalDate monday = LocalDate.of(2026, 1, 5);
        PropertyCalendar cal = calendar(UTC, weekdays9to5(), Map.of(), List.of(
                oneOff(at(UTC, monday, "10:00"), at(UTC, monday, "12:00"))));
        List<UnavailableRange> ranges = AvailabilityCalculator.unavailableRanges(
                cal, at(UTC, monday, "09:00"), at(UTC, monday, "17:00"));
        assertThat(ranges).hasSize(1);
        assertThat(ranges.get(0).cause()).isEqualTo(Cause.DOWNTIME);
        assertThat(ranges.get(0).start()).isEqualTo(at(UTC, monday, "10:00"));
        assertThat(ranges.get(0).end()).isEqualTo(at(UTC, monday, "12:00"));
    }

    @Test
    void unavailableRangesCoverNightsAndHolidays() {
        LocalDate tuesday = LocalDate.of(2026, 1, 6);
        PropertyCalendar cal = calendar(UTC, weekdays9to5(),
                Map.of(tuesday, holiday(tuesday, "Epiphany")), List.of());
        List<UnavailableRange> ranges = AvailabilityCalculator.unavailableRanges(
                cal, at(UTC, tuesday.minusDays(1), "12:00"), at(UTC, tuesday.plusDays(1), "12:00"));
        // Mon 17:00 → Wed 09:00 is one merged block; the holiday day inside it wins the cause
        assertThat(ranges).hasSize(1);
        assertThat(ranges.get(0).cause()).isEqualTo(Cause.HOLIDAY);
        assertThat(ranges.get(0).start()).isEqualTo(at(UTC, tuesday.minusDays(1), "17:00"));
        assertThat(ranges.get(0).end()).isEqualTo(at(UTC, tuesday.plusDays(1), "09:00"));
    }

    // ── next available slot ─────────────────────────────────────────────────

    @Test
    void nextSlotSkipsClosureAndHoliday() {
        LocalDate monday = LocalDate.of(2026, 1, 5);
        LocalDate wednesday = monday.plusDays(2);
        PropertyCalendar cal = calendar(UTC, weekdays9to5(),
                Map.of(wednesday, holiday(wednesday, "Holiday")),
                // closure covering Monday and Tuesday entirely
                List.of(oneOff(at(UTC, monday, "00:00"), at(UTC, wednesday, "00:00"))));
        Optional<Range> slot = AvailabilityCalculator.nextAvailableSlot(
                cal, at(UTC, monday, "09:00"), Duration.ofMinutes(60), 30);
        assertThat(slot).isPresent();
        assertThat(slot.get().start()).isEqualTo(at(UTC, monday.plusDays(3), "09:00"));
    }

    @Test
    void nextSlotRollsToNextDayWhenSegmentTooShort() {
        LocalDate monday = LocalDate.of(2026, 1, 5);
        PropertyCalendar cal = calendar(UTC, weekdays9to5(), Map.of(), List.of());
        Optional<Range> slot = AvailabilityCalculator.nextAvailableSlot(
                cal, at(UTC, monday, "16:30"), Duration.ofMinutes(60), 30);
        assertThat(slot).isPresent();
        assertThat(slot.get().start()).isEqualTo(at(UTC, monday.plusDays(1), "09:00"));
    }

    @Test
    void nextSlotEmptyWhenNothingFitsInHorizon() {
        // 30-minute daily shifts can never fit a 60-minute slot
        Map<DayOfWeek, List<ShiftDef>> hours = Map.of(
                DayOfWeek.MONDAY, List.of(new ShiftDef("09:00", "09:30")));
        PropertyCalendar cal = calendar(UTC, hours, Map.of(), List.of());
        assertThat(AvailabilityCalculator.nextAvailableSlot(
                cal, at(UTC, LocalDate.of(2026, 1, 5), "09:00"), Duration.ofMinutes(60), 30))
                .isEmpty();
    }

    @Test
    void slotStartsExactlyAtFromWhenAvailable() {
        LocalDate monday = LocalDate.of(2026, 1, 5);
        PropertyCalendar cal = calendar(UTC, weekdays9to5(), Map.of(), List.of());
        Optional<Range> slot = AvailabilityCalculator.nextAvailableSlot(
                cal, at(UTC, monday, "10:15"), Duration.ofMinutes(45), 30);
        assertThat(slot).isPresent();
        assertThat(slot.get().start()).isEqualTo(at(UTC, monday, "10:15"));
        assertThat(slot.get().end()).isEqualTo(at(UTC, monday, "11:00"));
    }
}
