package com.sclera.applicationplane.inspection.client;

import com.sclera.controlplane.common.dapr.DaprInvocationHelper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Availability queries against calendar-service over Dapr service invocation
 * (POST + body — Dapr rejects '?' in the invocation method name).
 * DaprInvocationHelper (from sclera-common) HMAC-signs the request; both
 * services must share the same sclera.event-listener.signing-secret.
 */
@Component
public class CalendarClient {

    private static final String CALENDAR_APP_ID = "sclera-calendar-service";

    public record CheckQuery(UUID propertyId, OffsetDateTime at, String featureCode,
                             String scopeType, UUID scopeId) {}

    public record NextSlotQuery(UUID propertyId, OffsetDateTime from, int durationMinutes,
                                String featureCode, String scopeType, UUID scopeId) {}

    public record Availability(OffsetDateTime at, boolean available, String cause, String detail) {}

    public record Slot(OffsetDateTime start, OffsetDateTime end) {}

    public record WorkingHoursQuery(UUID propertyId, LocalDate date) {}

    public record WorkingHours(LocalDate date, String timezone, boolean workingDay,
                               boolean holiday, String holidayName) {}

    private final DaprInvocationHelper daprInvocationHelper;

    public CalendarClient(DaprInvocationHelper daprInvocationHelper) {
        this.daprInvocationHelper = daprInvocationHelper;
    }

    public Availability check(UUID orgId, UUID propertyId, OffsetDateTime at, String featureCode) {
        return daprInvocationHelper.invoke(CALENDAR_APP_ID,
                "internal/api/v1/calendar/orgs/" + orgId + "/availability/check",
                new CheckQuery(propertyId, at, featureCode, null, null),
                Availability.class);
    }

    public Slot nextAvailableSlot(UUID orgId, UUID propertyId, OffsetDateTime from,
                                  int durationMinutes, String featureCode) {
        return daprInvocationHelper.invoke(CALENDAR_APP_ID,
                "internal/api/v1/calendar/orgs/" + orgId + "/availability/next-slot",
                new NextSlotQuery(propertyId, from, durationMinutes, featureCode, null, null),
                Slot.class);
    }
}
