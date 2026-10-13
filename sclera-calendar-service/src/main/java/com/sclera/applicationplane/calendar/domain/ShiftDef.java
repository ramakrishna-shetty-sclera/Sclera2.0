package com.sclera.applicationplane.calendar.domain;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * One working shift as wall-clock "HH:mm" strings (JSONB payload inside
 * calendar_settings.working_hours). end <= start means the shift crosses
 * midnight and ends on the following day.
 */
public class ShiftDef {

    private String start;
    private String end;

    public ShiftDef() {}

    public ShiftDef(String start, String end) {
        this.start = start;
        this.end = end;
    }

    public String getStart() { return start; }
    public void setStart(String start) { this.start = start; }
    public String getEnd() { return end; }
    public void setEnd(String end) { this.end = end; }

    public LocalTime startTime() { return LocalTime.parse(start); }
    public LocalTime endTime() { return LocalTime.parse(end); }

    public boolean crossesMidnight() {
        return !endTime().isAfter(startTime());
    }

    /** @throws IllegalArgumentException when either bound is not a valid HH:mm. */
    public void validate() {
        try {
            startTime();
            endTime();
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Shift times must be HH:mm (got "
                    + start + " - " + end + ")");
        }
    }
}
