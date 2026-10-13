package com.sclera.applicationplane.inspection.service;

import com.sclera.applicationplane.inspection.client.CalendarClient;
import com.sclera.controlplane.common.security.OrgContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The inspection-service side of the calendar opt-in (reference integration,
 * see docs/calendar-service.md §8). Feature code "inspection.checklist" —
 * admins tune which downtime types / holiday categories affect it via the
 * calendar's feature policies.
 *
 * FAIL-OPEN by design: when calendar-service is unreachable or
 * sclera.calendar.enabled=false, dates pass through unchanged and the
 * inspection flow behaves exactly as before the integration.
 */
@Component
public class CalendarGate {

    public static final String FEATURE_CHECKLIST = "inspection.checklist";

    private static final Logger log = LoggerFactory.getLogger(CalendarGate.class);

    private final CalendarClient client;
    private final boolean enabled;

    public CalendarGate(CalendarClient client,
                        @Value("${sclera.calendar.enabled:true}") boolean enabled) {
        this.client = client;
        this.enabled = enabled;
    }

    /**
     * Returns the requested due date if it falls in available time, otherwise
     * the start of the next available slot. Null in, null out.
     */
    public OffsetDateTime adjustDueDate(OffsetDateTime requested, String featureCode) {
        if (!enabled || requested == null) {
            return requested;
        }
        UUID orgId = OrgContext.getOrgId();
        try {
            CalendarClient.Availability availability =
                    client.check(orgId, null, requested, featureCode);
            if (availability == null || availability.available()) {
                return requested;
            }
            CalendarClient.Slot slot =
                    client.nextAvailableSlot(orgId, null, requested, 1, featureCode);
            if (slot != null && slot.start() != null) {
                log.info("Due date {} falls in unavailable time ({}); moved to {}",
                        requested, availability.cause(), slot.start());
                return slot.start();
            }
            return requested;
        } catch (Exception e) {
            log.warn("Calendar availability check failed — keeping requested due date {}: {}",
                    requested, e.getMessage());
            return requested;
        }
    }
}
