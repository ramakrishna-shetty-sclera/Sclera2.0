package com.sclera.applicationplane.calendar.engine;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceEngineTest {

    private static RecurrenceRule rule(RecurrenceRule.Freq freq, int interval) {
        RecurrenceRule r = new RecurrenceRule();
        r.setFreq(freq);
        r.setInterval(interval);
        return r;
    }

    @Test
    void dailyWithInterval() {
        RecurrenceRule r = rule(RecurrenceRule.Freq.DAILY, 3);
        List<LocalDate> dates = RecurrenceEngine.occurrencesBetween(
                r, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10));
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 4),
                LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 10));
    }

    @Test
    void weeklyByDaysEveryOtherWeek() {
        RecurrenceRule r = rule(RecurrenceRule.Freq.WEEKLY, 2);
        r.setByDays(Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
        // series starts Mon 2026-01-05 (ISO week anchored on Monday)
        List<LocalDate> dates = RecurrenceEngine.occurrencesBetween(
                r, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 1));
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9),      // week 0
                LocalDate.of(2026, 1, 19), LocalDate.of(2026, 1, 23));   // week 2
    }

    @Test
    void weeklyWithoutByDaysUsesSeriesStartWeekday() {
        RecurrenceRule r = rule(RecurrenceRule.Freq.WEEKLY, 1);
        List<LocalDate> dates = RecurrenceEngine.occurrencesBetween(
                r, LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertThat(dates).allMatch(d -> d.getDayOfWeek() == DayOfWeek.WEDNESDAY);
        assertThat(dates).hasSize(4);
    }

    @Test
    void monthlyOn31stSkipsShortMonths() {
        RecurrenceRule r = rule(RecurrenceRule.Freq.MONTHLY, 1);
        r.setByMonthDay(31);
        List<LocalDate> dates = RecurrenceEngine.occurrencesBetween(
                r, LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        // Feb, Apr, Jun have no 31st — skipped, per RFC 5545
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 5, 31));
    }

    @Test
    void monthlyThirdMonday() {
        RecurrenceRule r = rule(RecurrenceRule.Freq.MONTHLY, 1);
        r.setByDays(Set.of(DayOfWeek.MONDAY));
        r.setBySetPos(3);
        List<LocalDate> dates = RecurrenceEngine.occurrencesBetween(
                r, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        assertThat(dates).containsExactly(
                LocalDate.of(2026, 1, 19), LocalDate.of(2026, 2, 16), LocalDate.of(2026, 3, 16));
    }

    @Test
    void monthlyLastFriday() {
        RecurrenceRule r = rule(RecurrenceRule.Freq.MONTHLY, 1);
        r.setByDays(Set.of(DayOfWeek.FRIDAY));
        r.setBySetPos(-1);
        List<LocalDate> dates = RecurrenceEngine.occurrencesBetween(
                r, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28));
        assertThat(dates).containsExactly(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 2, 27));
    }

    @Test
    void yearlyLeapDayOnlyInLeapYears() {
        RecurrenceRule r = rule(RecurrenceRule.Freq.YEARLY, 1);
        r.setMonth(2);
        r.setByMonthDay(29);
        List<LocalDate> dates = RecurrenceEngine.occurrencesBetween(
                r, LocalDate.of(2024, 2, 29), LocalDate.of(2024, 1, 1), LocalDate.of(2029, 12, 31));
        // downtime recurrences skip non-leap years (holidays observe Feb 28 via HolidayResolver)
        assertThat(dates).containsExactly(LocalDate.of(2024, 2, 29), LocalDate.of(2028, 2, 29));
    }

    @Test
    void untilIsInclusiveAndCountConsumesEarlyOccurrences() {
        RecurrenceRule until = rule(RecurrenceRule.Freq.DAILY, 1);
        until.setUntil(LocalDate.of(2026, 1, 3));
        assertThat(RecurrenceEngine.occurrencesBetween(
                until, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .hasSize(3);

        RecurrenceRule count = rule(RecurrenceRule.Freq.DAILY, 1);
        count.setCount(5);
        // occurrences before the query window still consume COUNT
        assertThat(RecurrenceEngine.occurrencesBetween(
                count, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 4), LocalDate.of(2026, 1, 31)))
                .containsExactly(LocalDate.of(2026, 1, 4), LocalDate.of(2026, 1, 5));
    }

    @Test
    void invalidRulesRejected() {
        RecurrenceRule noFreq = new RecurrenceRule();
        assertThatThrownBy(noFreq::validate).isInstanceOf(IllegalArgumentException.class);

        RecurrenceRule badSetPos = rule(RecurrenceRule.Freq.MONTHLY, 1);
        badSetPos.setBySetPos(5);
        badSetPos.setByDays(Set.of(DayOfWeek.MONDAY));
        assertThatThrownBy(badSetPos::validate).isInstanceOf(IllegalArgumentException.class);

        RecurrenceRule setPosWithoutDay = rule(RecurrenceRule.Freq.MONTHLY, 1);
        setPosWithoutDay.setBySetPos(2);
        assertThatThrownBy(setPosWithoutDay::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
