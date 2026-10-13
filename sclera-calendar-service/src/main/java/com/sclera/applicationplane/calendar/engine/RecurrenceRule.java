package com.sclera.applicationplane.calendar.engine;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Structured recurrence rule — the RFC 5545 subset the product needs
 * (FREQ / INTERVAL / BYDAY / BYMONTHDAY / BYSETPOS / UNTIL / COUNT), stored as
 * JSONB on downtime_window and validated at the API boundary instead of inside
 * a string parser. See docs/calendar-service.md §6 for the library trade-off.
 */
public class RecurrenceRule {

    public enum Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    private Freq freq;
    private int interval = 1;

    /** WEEKLY: the weekdays; MONTHLY/YEARLY with setPos: the single weekday. */
    private Set<DayOfWeek> byDays;

    /** MONTHLY/YEARLY: day of month (months without it are skipped, per RFC). */
    private Integer byMonthDay;

    /** MONTHLY/YEARLY: nth weekday of the month, 1..4 or -1 = last (with byDays). */
    private Integer bySetPos;

    /** YEARLY only: 1..12 (defaults to the series start's month). */
    private Integer month;

    /** Last occurrence date, inclusive. */
    private LocalDate until;

    /** Max number of occurrences counted from the series start. */
    private Integer count;

    public Freq getFreq() { return freq; }
    public void setFreq(Freq freq) { this.freq = freq; }
    public int getInterval() { return interval; }
    public void setInterval(int interval) { this.interval = interval; }
    public Set<DayOfWeek> getByDays() { return byDays; }
    public void setByDays(Set<DayOfWeek> byDays) { this.byDays = byDays; }
    public Integer getByMonthDay() { return byMonthDay; }
    public void setByMonthDay(Integer byMonthDay) { this.byMonthDay = byMonthDay; }
    public Integer getBySetPos() { return bySetPos; }
    public void setBySetPos(Integer bySetPos) { this.bySetPos = bySetPos; }
    public Integer getMonth() { return month; }
    public void setMonth(Integer month) { this.month = month; }
    public LocalDate getUntil() { return until; }
    public void setUntil(LocalDate until) { this.until = until; }
    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }

    /** @throws IllegalArgumentException on an inconsistent rule. */
    public void validate() {
        if (freq == null) {
            throw new IllegalArgumentException("Recurrence rule requires freq");
        }
        if (interval < 1) {
            throw new IllegalArgumentException("Recurrence interval must be >= 1");
        }
        if (bySetPos != null) {
            if (bySetPos != -1 && (bySetPos < 1 || bySetPos > 4)) {
                throw new IllegalArgumentException("bySetPos must be 1..4 or -1 (last)");
            }
            if (byDays == null || byDays.size() != 1) {
                throw new IllegalArgumentException("bySetPos requires exactly one byDays entry");
            }
            if (byMonthDay != null) {
                throw new IllegalArgumentException("bySetPos and byMonthDay are mutually exclusive");
            }
        }
        if (byMonthDay != null && (byMonthDay < 1 || byMonthDay > 31)) {
            throw new IllegalArgumentException("byMonthDay must be 1..31");
        }
        if (month != null && (month < 1 || month > 12)) {
            throw new IllegalArgumentException("month must be 1..12");
        }
        if (count != null && count < 1) {
            throw new IllegalArgumentException("count must be >= 1");
        }
    }
}
