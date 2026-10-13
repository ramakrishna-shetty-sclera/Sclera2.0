package com.sclera.applicationplane.calendar.engine;

import com.sclera.applicationplane.calendar.domain.DowntimeWindow;
import com.sclera.applicationplane.calendar.domain.ShiftDef;
import com.sclera.applicationplane.calendar.engine.HolidayResolver.ResolvedHoliday;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure interval math over a {@link PropertyCalendar} snapshot (settings +
 * resolved holidays + downtime already filtered by the caller's feature
 * policy). Everything resolves wall-clock rules to instants through
 * java.time's zone semantics: a shift start inside a DST spring-forward gap
 * moves to the first valid instant, an ambiguous fall-back time takes the
 * earlier offset, and cross-midnight shifts end on the following date.
 *
 * Precedence of causes when unavailable ranges overlap:
 * DOWNTIME > HOLIDAY > NON_WORKING_HOURS.
 */
public final class AvailabilityCalculator {

    public enum Cause { DOWNTIME, HOLIDAY, NON_WORKING_HOURS }

    public record Range(Instant start, Instant end) {
        public boolean contains(Instant at) {
            return !at.isBefore(start) && at.isBefore(end);
        }
        public boolean overlaps(Range other) {
            return start.isBefore(other.end) && other.start.isBefore(end);
        }
    }

    public record UnavailableRange(Instant start, Instant end, Cause cause, String detail) {}

    /**
     * Rule snapshot for one property. workingHours empty map = feature ignores
     * working hours (always "inside hours"); holidays/downtime lists are
     * pre-filtered by the feature policy.
     */
    public record PropertyCalendar(
            ZoneId zone,
            Map<DayOfWeek, List<ShiftDef>> workingHours,
            boolean respectWorkingHours,
            Map<LocalDate, ResolvedHoliday> holidays,
            List<DowntimeWindow> downtime) {

        public ResolvedHoliday holidayOn(LocalDate date) {
            return holidays.get(date);
        }
    }

    private AvailabilityCalculator() {}

    // ── working segments ────────────────────────────────────────────────────

    /**
     * The shift segments that START on the given local date, as instants.
     * A holiday date contributes none. A cross-midnight shift's tail belongs
     * to its start date.
     */
    public static List<Range> workingSegments(PropertyCalendar cal, LocalDate date) {
        if (cal.holidayOn(date) != null) {
            return List.of();
        }
        List<ShiftDef> shifts = cal.workingHours().get(date.getDayOfWeek());
        if (shifts == null || shifts.isEmpty()) {
            return List.of();
        }
        List<Range> segments = new ArrayList<>();
        for (ShiftDef shift : shifts) {
            ZonedDateTime start = ZonedDateTime.of(date, shift.startTime(), cal.zone());
            LocalDate endDate = shift.crossesMidnight() ? date.plusDays(1) : date;
            ZonedDateTime end = ZonedDateTime.of(endDate, shift.endTime(), cal.zone());
            if (end.isAfter(start)) {
                segments.add(new Range(start.toInstant(), end.toInstant()));
            }
        }
        return union(segments);
    }

    /** Union of working segments whose start date falls in [fromDate, toDate]. */
    public static List<Range> workingUnion(PropertyCalendar cal, LocalDate fromDate, LocalDate toDate) {
        List<Range> all = new ArrayList<>();
        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            all.addAll(workingSegments(cal, d));
        }
        return union(all);
    }

    // ── downtime ────────────────────────────────────────────────────────────

    /** Concrete occurrences of every downtime window intersecting [from, to). */
    public static List<Range> downtimeOccurrences(PropertyCalendar cal, Instant from, Instant to) {
        List<Range> result = new ArrayList<>();
        Range query = new Range(from, to);
        LocalDate fromDate = from.atZone(cal.zone()).toLocalDate().minusDays(1);
        LocalDate toDate = to.atZone(cal.zone()).toLocalDate().plusDays(1);
        for (DowntimeWindow w : cal.downtime()) {
            if (!w.isActive()) {
                continue;
            }
            if (w.getKind() == DowntimeWindow.Kind.ONE_OFF) {
                Range r = new Range(w.getStartAt().toInstant(), w.getEndAt().toInstant());
                if (r.overlaps(query)) {
                    result.add(r);
                }
            } else {
                for (LocalDate d : RecurrenceEngine.occurrencesBetween(
                        w.getRecurrence(), w.getSeriesStart(), fromDate, toDate)) {
                    Instant start = ZonedDateTime.of(d, w.getLocalStartTime(), cal.zone()).toInstant();
                    // duration is elapsed time, so a 2h window is 2h even across a DST shift
                    Range r = new Range(start, start.plus(Duration.ofMinutes(w.getDurationMinutes())));
                    if (r.overlaps(query)) {
                        result.add(r);
                    }
                }
            }
        }
        return union(result);
    }

    // ── queries ─────────────────────────────────────────────────────────────

    /** First blocking cause at the instant, or empty = available. */
    public static Optional<Cause> blockingCause(PropertyCalendar cal, Instant at) {
        for (Range r : downtimeOccurrences(cal, at, at.plusMillis(1))) {
            if (r.contains(at)) {
                return Optional.of(Cause.DOWNTIME);
            }
        }
        LocalDate date = at.atZone(cal.zone()).toLocalDate();
        if (cal.holidayOn(date) != null) {
            return Optional.of(Cause.HOLIDAY);
        }
        if (cal.respectWorkingHours()) {
            // yesterday too: a cross-midnight shift can cover this instant
            List<Range> segments = workingUnion(cal, date.minusDays(1), date);
            if (segments.stream().noneMatch(r -> r.contains(at))) {
                return Optional.of(Cause.NON_WORKING_HOURS);
            }
        }
        return Optional.empty();
    }

    /**
     * Merged unavailable ranges within [from, to). Overlapping contributors
     * merge into one range carrying the highest-precedence cause.
     */
    public static List<UnavailableRange> unavailableRanges(PropertyCalendar cal, Instant from, Instant to) {
        LocalDate fromDate = from.atZone(cal.zone()).toLocalDate();
        LocalDate toDate = to.atZone(cal.zone()).toLocalDate();

        List<UnavailableRange> contributors = new ArrayList<>();
        if (cal.respectWorkingHours()) {
            List<Range> working = workingUnion(cal, fromDate.minusDays(1), toDate);
            for (Range gap : complement(working, from, to)) {
                contributors.add(new UnavailableRange(gap.start(), gap.end(), Cause.NON_WORKING_HOURS, null));
            }
        }
        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            ResolvedHoliday holiday = cal.holidayOn(d);
            if (holiday != null) {
                Instant dayStart = d.atStartOfDay(cal.zone()).toInstant();
                Instant dayEnd = d.plusDays(1).atStartOfDay(cal.zone()).toInstant();
                Range clipped = clip(new Range(dayStart, dayEnd), from, to);
                if (clipped != null) {
                    contributors.add(new UnavailableRange(
                            clipped.start(), clipped.end(), Cause.HOLIDAY, holiday.name()));
                }
            }
        }
        for (Range r : downtimeOccurrences(cal, from, to)) {
            Range clipped = clip(r, from, to);
            if (clipped != null) {
                contributors.add(new UnavailableRange(clipped.start(), clipped.end(), Cause.DOWNTIME, null));
            }
        }
        return mergeWithPrecedence(contributors);
    }

    /**
     * Earliest range of the requested duration that is fully available, at or
     * after {@code from}, within {@code horizonDays}. Adjacent working segments
     * (e.g. a cross-midnight shift meeting the next morning's) chain naturally
     * through the union.
     */
    public static Optional<Range> nextAvailableSlot(PropertyCalendar cal, Instant from,
                                                    Duration duration, int horizonDays) {
        LocalDate fromDate = from.atZone(cal.zone()).toLocalDate();
        Instant horizon = fromDate.plusDays(horizonDays).atStartOfDay(cal.zone()).toInstant();

        List<Range> available;
        if (cal.respectWorkingHours()) {
            available = workingUnion(cal, fromDate.minusDays(1), fromDate.plusDays(horizonDays));
        } else {
            available = new ArrayList<>(List.of(new Range(from, horizon)));
            // holidays still block when the policy respects them (holiday map is pre-filtered)
            for (LocalDate d = fromDate; !d.isAfter(fromDate.plusDays(horizonDays)); d = d.plusDays(1)) {
                if (cal.holidayOn(d) != null) {
                    available = subtract(available, List.of(new Range(
                            d.atStartOfDay(cal.zone()).toInstant(),
                            d.plusDays(1).atStartOfDay(cal.zone()).toInstant())));
                }
            }
        }
        available = subtract(available, downtimeOccurrences(cal, from.minus(Duration.ofDays(1)), horizon));

        for (Range r : available) {
            Instant start = r.start().isBefore(from) ? from : r.start();
            if (!start.plus(duration).isAfter(r.end())) {
                return Optional.of(new Range(start, start.plus(duration)));
            }
        }
        return Optional.empty();
    }

    // ── interval utilities ──────────────────────────────────────────────────

    /** Sorts and unions overlapping/adjacent ranges. */
    public static List<Range> union(List<Range> ranges) {
        if (ranges.size() <= 1) {
            return new ArrayList<>(ranges);
        }
        List<Range> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparing(Range::start));
        List<Range> merged = new ArrayList<>();
        Range current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Range next = sorted.get(i);
            if (!next.start().isAfter(current.end())) {
                if (next.end().isAfter(current.end())) {
                    current = new Range(current.start(), next.end());
                }
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    /** base minus cuts; both are expected sorted/unioned. */
    public static List<Range> subtract(List<Range> base, List<Range> cuts) {
        List<Range> result = new ArrayList<>();
        for (Range b : base) {
            Instant cursor = b.start();
            for (Range c : cuts) {
                if (!c.overlaps(new Range(cursor, b.end()))) {
                    continue;
                }
                if (c.start().isAfter(cursor)) {
                    result.add(new Range(cursor, c.start()));
                }
                if (c.end().isAfter(cursor)) {
                    cursor = c.end();
                }
                if (!cursor.isBefore(b.end())) {
                    break;
                }
            }
            if (cursor.isBefore(b.end())) {
                result.add(new Range(cursor, b.end()));
            }
        }
        return result;
    }

    /** Gaps of {@code covered} within [from, to). */
    public static List<Range> complement(List<Range> covered, Instant from, Instant to) {
        return subtract(List.of(new Range(from, to)), union(covered));
    }

    private static Range clip(Range r, Instant from, Instant to) {
        Instant s = r.start().isBefore(from) ? from : r.start();
        Instant e = r.end().isAfter(to) ? to : r.end();
        return s.isBefore(e) ? new Range(s, e) : null;
    }

    private static List<UnavailableRange> mergeWithPrecedence(List<UnavailableRange> contributors) {
        if (contributors.isEmpty()) {
            return List.of();
        }
        List<UnavailableRange> sorted = new ArrayList<>(contributors);
        sorted.sort(Comparator.comparing(UnavailableRange::start));
        List<UnavailableRange> merged = new ArrayList<>();
        UnavailableRange current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            UnavailableRange next = sorted.get(i);
            if (!next.start().isAfter(current.end())) {
                boolean nextWins = next.cause().ordinal() < current.cause().ordinal();
                current = new UnavailableRange(
                        current.start(),
                        next.end().isAfter(current.end()) ? next.end() : current.end(),
                        nextWins ? next.cause() : current.cause(),
                        nextWins ? next.detail() : current.detail());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }
}
