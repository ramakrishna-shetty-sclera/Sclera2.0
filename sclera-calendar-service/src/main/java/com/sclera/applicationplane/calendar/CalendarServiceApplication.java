package com.sclera.applicationplane.calendar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Calendar service — the source of truth for per-property date/time rules:
 * timezone + locale, working hours (multiple shifts, cross-midnight), working
 * vs. non-working days, fiscal year, week numbering, downtime windows
 * (one-off + recurring) and holidays (fixed, floating, imported), plus the
 * availability queries other features consult instead of hardcoding rules.
 *
 * The "com.sclera.controlplane.common" scan entry is mandatory — without it the
 * shared beans (response envelope, filters, error handling) silently never load.
 */
@SpringBootApplication(
        scanBasePackages = {
                "com.sclera.applicationplane.calendar",
                "com.sclera.controlplane.common"
        })
public class CalendarServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalendarServiceApplication.class, args);
    }
}
