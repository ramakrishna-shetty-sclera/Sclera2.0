package com.sclera.applicationplane.inspection.service;

import com.sclera.applicationplane.inspection.client.CalendarClient;
import com.sclera.controlplane.common.security.OrgContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CalendarGateTest {

    private final CalendarClient client = mock(CalendarClient.class);
    private final OffsetDateTime requested = OffsetDateTime.parse("2026-01-05T11:00:00Z");

    @BeforeEach
    void orgContext() {
        OrgContext.setOrgId(UUID.randomUUID());
        OrgContext.setUserId(UUID.randomUUID());
    }

    @AfterEach
    void clear() {
        OrgContext.clear();
    }

    @Test
    void disabledGatePassesDatesThroughUntouched() {
        CalendarGate gate = new CalendarGate(client, false);
        assertThat(gate.adjustDueDate(requested, CalendarGate.FEATURE_CHECKLIST)).isEqualTo(requested);
        verifyNoInteractions(client);
    }

    @Test
    void availableDueDateIsKept() {
        when(client.check(any(), any(), any(), anyString()))
                .thenReturn(new CalendarClient.Availability(requested, true, null, null));
        CalendarGate gate = new CalendarGate(client, true);
        assertThat(gate.adjustDueDate(requested, CalendarGate.FEATURE_CHECKLIST)).isEqualTo(requested);
    }

    @Test
    void unavailableDueDateMovesToNextSlot() {
        OffsetDateTime slotStart = OffsetDateTime.parse("2026-01-06T09:00:00Z");
        when(client.check(any(), any(), any(), anyString()))
                .thenReturn(new CalendarClient.Availability(requested, false, "HOLIDAY", "Epiphany"));
        when(client.nextAvailableSlot(any(), any(), any(), anyInt(), anyString()))
                .thenReturn(new CalendarClient.Slot(slotStart, slotStart.plusMinutes(1)));
        CalendarGate gate = new CalendarGate(client, true);
        assertThat(gate.adjustDueDate(requested, CalendarGate.FEATURE_CHECKLIST)).isEqualTo(slotStart);
    }

    @Test
    void calendarFailureFailsOpen() {
        when(client.check(any(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("calendar-service unreachable"));
        CalendarGate gate = new CalendarGate(client, true);
        assertThat(gate.adjustDueDate(requested, CalendarGate.FEATURE_CHECKLIST)).isEqualTo(requested);
    }

    @Test
    void nullDueDateStaysNull() {
        CalendarGate gate = new CalendarGate(client, true);
        assertThat(gate.adjustDueDate(null, CalendarGate.FEATURE_CHECKLIST)).isNull();
        verifyNoInteractions(client);
    }
}
