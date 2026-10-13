package com.sclera.applicationplane.calendar.engine;

import com.sclera.applicationplane.calendar.domain.Holiday;
import com.sclera.applicationplane.calendar.domain.HolidayOverride;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns holiday definitions into concrete dates for a range, applying
 * per-property overrides (DISABLE / MOVE, per year or every year).
 *
 * Leap-year rule: a FIXED Feb 29 holiday is observed on Feb 28 in non-leap
 * years (documented in docs/calendar-service.md §9).
 */
public final class HolidayResolver {

    public record ResolvedHoliday(LocalDate date, UUID holidayId, String name, String category) {}

    private HolidayResolver() {}

    /**
     * @param holidays  enabled definitions applicable to the property (org-wide + property-specific)
     * @param overrides the property's overrides
     */
    public static List<ResolvedHoliday> resolve(List<Holiday> holidays, List<HolidayOverride> overrides,
                                                LocalDate from, LocalDate to) {
        List<ResolvedHoliday> result = new ArrayList<>();
        for (Holiday h : holidays) {
            // widen by a year so a MOVE into the range from an out-of-range nominal date is caught
            for (int year = from.getYear() - 1; year <= to.getYear() + 1; year++) {
                LocalDate nominal = dateIn(h, year);
                if (nominal == null) {
                    continue;
                }
                LocalDate effective = nominal;
                boolean disabled = false;
                for (HolidayOverride o : overrides) {
                    if (!o.getHolidayId().equals(h.getId())) {
                        continue;
                    }
                    if (o.getYear() != null && o.getYear() != nominal.getYear()) {
                        continue;
                    }
                    if (o.getAction() == HolidayOverride.Action.DISABLE) {
                        disabled = true;
                    } else {
                        effective = o.getMovedTo();
                    }
                }
                if (disabled || effective == null) {
                    continue;
                }
                if (!effective.isBefore(from) && !effective.isAfter(to)) {
                    result.add(new ResolvedHoliday(effective, h.getId(), h.getName(), h.getCategory()));
                }
            }
        }
        result.sort((a, b) -> a.date().compareTo(b.date()));
        return result;
    }

    /** The holiday's nominal date in the given year, or null when it has none. */
    static LocalDate dateIn(Holiday h, int year) {
        return switch (h.getDateType()) {
            case FIXED -> {
                int day = h.getDayOfMonth();
                YearMonth ym = YearMonth.of(year, h.getMonth());
                if (day > ym.lengthOfMonth()) {
                    // Feb 29 observed Feb 28 (and a hypothetical Apr 31 clamps too)
                    day = ym.lengthOfMonth();
                }
                yield LocalDate.of(year, h.getMonth(), day);
            }
            case FLOATING -> RecurrenceEngine.nthWeekdayOfMonth(
                    year, h.getMonth(), h.getDayOfWeek(), h.getWeekOrdinal());
            case ONE_OFF -> h.getOnDate().getYear() == year ? h.getOnDate() : null;
        };
    }

    /** Validation shared by API + import paths. */
    public static void validateDefinition(Holiday h) {
        switch (h.getDateType()) {
            case FIXED -> {
                require(h.getMonth() != null && h.getDayOfMonth() != null,
                        "FIXED holiday requires month and dayOfMonth");
                int max = Month.of(h.getMonth()).maxLength();
                require(h.getDayOfMonth() >= 1 && h.getDayOfMonth() <= max,
                        "dayOfMonth out of range for month " + h.getMonth());
            }
            case FLOATING -> {
                require(h.getMonth() != null && h.getWeekOrdinal() != null && h.getDayOfWeek() != null,
                        "FLOATING holiday requires month, weekOrdinal and dayOfWeek");
                require(h.getWeekOrdinal() == -1 || (h.getWeekOrdinal() >= 1 && h.getWeekOrdinal() <= 4),
                        "weekOrdinal must be 1..4 or -1 (last)");
            }
            case ONE_OFF -> require(h.getOnDate() != null, "ONE_OFF holiday requires onDate");
        }
        require(h.getMonth() == null || (h.getMonth() >= 1 && h.getMonth() <= 12), "month must be 1..12");
        Objects.requireNonNull(h.getName(), "name");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
