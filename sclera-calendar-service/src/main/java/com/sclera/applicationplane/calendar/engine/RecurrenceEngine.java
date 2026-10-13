package com.sclera.applicationplane.calendar.engine;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Expands a {@link RecurrenceRule} into concrete dates by walking days from the
 * series start — O(range) with a hard {@link #HORIZON_YEARS} cap, which for
 * query windows of weeks/months is well under a millisecond and immune to the
 * month-length / DST arithmetic traps of jump-ahead implementations.
 *
 * Week boundaries for WEEKLY interval math are ISO (Monday-start), independent
 * of the display-level firstDayOfWeek setting.
 */
public final class RecurrenceEngine {

    public static final int HORIZON_YEARS = 10;

    private RecurrenceEngine() {}

    /**
     * Occurrence dates within [from, to] (both inclusive). COUNT is honoured
     * from the series start, so occurrences before {@code from} still consume it.
     */
    public static List<LocalDate> occurrencesBetween(RecurrenceRule rule, LocalDate seriesStart,
                                                     LocalDate from, LocalDate to) {
        rule.validate();
        List<LocalDate> result = new ArrayList<>();
        LocalDate horizon = seriesStart.plusYears(HORIZON_YEARS);
        LocalDate end = min(to, rule.getUntil() != null ? rule.getUntil() : horizon, horizon);
        int seen = 0;
        for (LocalDate d = seriesStart; !d.isAfter(end); d = d.plusDays(1)) {
            if (!matches(rule, seriesStart, d)) {
                continue;
            }
            seen++;
            if (rule.getCount() != null && seen > rule.getCount()) {
                break;
            }
            if (!d.isBefore(from)) {
                result.add(d);
            }
        }
        return result;
    }

    static boolean matches(RecurrenceRule rule, LocalDate start, LocalDate d) {
        return switch (rule.getFreq()) {
            case DAILY -> ChronoUnit.DAYS.between(start, d) % rule.getInterval() == 0;
            case WEEKLY -> {
                Set<DayOfWeek> days = (rule.getByDays() == null || rule.getByDays().isEmpty())
                        ? Set.of(start.getDayOfWeek()) : rule.getByDays();
                if (!days.contains(d.getDayOfWeek())) {
                    yield false;
                }
                long weeks = ChronoUnit.WEEKS.between(mondayOf(start), mondayOf(d));
                yield weeks % rule.getInterval() == 0;
            }
            case MONTHLY -> {
                long months = ChronoUnit.MONTHS.between(start.withDayOfMonth(1), d.withDayOfMonth(1));
                if (months % rule.getInterval() != 0) {
                    yield false;
                }
                yield matchesDayInMonth(rule, start, d);
            }
            case YEARLY -> {
                if ((d.getYear() - start.getYear()) % rule.getInterval() != 0) {
                    yield false;
                }
                int month = rule.getMonth() != null ? rule.getMonth() : start.getMonthValue();
                if (d.getMonthValue() != month) {
                    yield false;
                }
                yield matchesDayInMonth(rule, start, d);
            }
        };
    }

    private static boolean matchesDayInMonth(RecurrenceRule rule, LocalDate start, LocalDate d) {
        if (rule.getBySetPos() != null) {
            DayOfWeek dow = rule.getByDays().iterator().next();
            return d.equals(nthWeekdayOfMonth(d.getYear(), d.getMonthValue(), dow, rule.getBySetPos()));
        }
        // a month without the requested day (e.g. the 31st) is skipped, per RFC 5545
        int day = rule.getByMonthDay() != null ? rule.getByMonthDay() : start.getDayOfMonth();
        return d.getDayOfMonth() == day;
    }

    /** nth (1..4) or last (-1) weekday of a month. */
    public static LocalDate nthWeekdayOfMonth(int year, int month, DayOfWeek dow, int ordinal) {
        LocalDate first = LocalDate.of(year, month, 1);
        return ordinal == -1
                ? first.with(TemporalAdjusters.lastInMonth(dow))
                : first.with(TemporalAdjusters.dayOfWeekInMonth(ordinal, dow));
    }

    private static LocalDate mondayOf(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static LocalDate min(LocalDate a, LocalDate b, LocalDate c) {
        LocalDate m = a.isBefore(b) ? a : b;
        return m.isBefore(c) ? m : c;
    }
}
