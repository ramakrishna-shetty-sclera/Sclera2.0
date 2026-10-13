package com.sclera.applicationplane.calendar.service;

import com.sclera.applicationplane.calendar.domain.DowntimeWindow;
import com.sclera.applicationplane.calendar.domain.ShiftDef;
import com.sclera.applicationplane.calendar.domain.WeekNumbering;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.AvailabilityResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.DowntimeRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.HolidayImportRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.HolidayResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.NextSlotResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.OverrideRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.PolicyRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.ResolvedHolidayResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.SettingsRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.UnavailableRangeResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.WorkingHoursResponse;
import com.sclera.applicationplane.calendar.domain.HolidayOverride;
import com.sclera.applicationplane.calendar.engine.AvailabilityCalculator.Cause;
import com.sclera.applicationplane.calendar.support.PostgresIntegrationTest;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarServiceIT extends PostgresIntegrationTest {

    @Autowired CalendarSettingsService settingsService;
    @Autowired HolidayService holidayService;
    @Autowired DowntimeService downtimeService;
    @Autowired FeaturePolicyService policyService;
    @Autowired AvailabilityService availabilityService;

    private static SettingsRequest nySettings(UUID propertyId) {
        ShiftDef nineToFive = new ShiftDef("09:00", "17:00");
        return new SettingsRequest(propertyId, "America/New_York", true, "MM/dd/yyyy",
                DayOfWeek.SUNDAY, WeekNumbering.US, 4, 1,
                Map.of(DayOfWeek.MONDAY, List.of(nineToFive),
                        DayOfWeek.TUESDAY, List.of(nineToFive),
                        DayOfWeek.WEDNESDAY, List.of(nineToFive),
                        DayOfWeek.THURSDAY, List.of(nineToFive),
                        DayOfWeek.FRIDAY, List.of(nineToFive)));
    }

    @Test
    void effectiveSettingsFallBackFromPropertyToOrgToBuiltIn() {
        actAsNewOrg();
        UUID property = UUID.randomUUID();

        // nothing configured → built-in defaults
        EffectiveSettings builtIn = settingsService.effective(property);
        assertThat(builtIn.source()).isEqualTo(EffectiveSettings.SOURCE_BUILT_IN);
        assertThat(builtIn.zone().getId()).isEqualTo("UTC");

        // org default configured → ORG_DEFAULT
        settingsService.upsert(nySettings(null));
        assertThat(settingsService.effective(property).source())
                .isEqualTo(EffectiveSettings.SOURCE_ORG_DEFAULT);

        // property row wins over the org default
        SettingsRequest tokyo = new SettingsRequest(property, "Asia/Tokyo", true, null,
                null, null, null, null,
                Map.of(DayOfWeek.MONDAY, List.of(new ShiftDef("08:00", "16:00"))));
        settingsService.upsert(tokyo);
        EffectiveSettings effective = settingsService.effective(property);
        assertThat(effective.source()).isEqualTo(EffectiveSettings.SOURCE_PROPERTY);
        assertThat(effective.zone().getId()).isEqualTo("Asia/Tokyo");
    }

    @Test
    void upsertRejectsBadTimezoneAndBadShifts() {
        actAsNewOrg();
        assertThatThrownBy(() -> settingsService.upsert(new SettingsRequest(null, "Mars/Olympus",
                null, null, null, null, null, null, Map.of())))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> settingsService.upsert(new SettingsRequest(null, "UTC",
                null, null, null, null, null, null,
                Map.of(DayOfWeek.MONDAY, List.of(new ShiftDef("9am", "5pm"))))))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void holidayImportResolveAndPropertyOverride() {
        actAsNewOrg();
        UUID property = UUID.randomUUID();
        List<HolidayResponse> imported = holidayService.importRegion(new HolidayImportRequest("US", null));
        assertThat(imported).isNotEmpty();

        // 3rd Monday of January 2026 = Jan 19
        List<ResolvedHolidayResponse> resolved = holidayService.resolved(
                property, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertThat(resolved).extracting(ResolvedHolidayResponse::date)
                .contains(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 19));

        // property disables MLK Day → gone for that property, still there org-wide
        UUID mlk = resolved.stream()
                .filter(r -> r.date().equals(LocalDate.of(2026, 1, 19)))
                .findFirst().orElseThrow().holidayId();
        holidayService.addOverride(mlk, new OverrideRequest(
                property, HolidayOverride.Action.DISABLE, null, null));
        assertThat(holidayService.resolved(property, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .extracting(ResolvedHolidayResponse::date)
                .doesNotContain(LocalDate.of(2026, 1, 19));
        assertThat(holidayService.resolved(null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .extracting(ResolvedHolidayResponse::date)
                .contains(LocalDate.of(2026, 1, 19));

        // re-import replaces rather than duplicates
        holidayService.importRegion(new HolidayImportRequest("US", null));
        assertThat(holidayService.list(null)).hasSize(imported.size());
    }

    @Test
    void availabilityRespectsDowntimeHolidayAndWorkingHours() {
        actAsNewOrg();
        settingsService.upsert(nySettings(null));   // org default, America/New_York Mon–Fri 9–17

        // Mon 2026-01-05 10:00–12:00 New York maintenance
        OffsetDateTime downStart = OffsetDateTime.parse("2026-01-05T10:00:00-05:00");
        downtimeService.create(new DowntimeRequest(null, null, null,
                DowntimeWindow.Type.MAINTENANCE, "quarterly maintenance",
                DowntimeWindow.Kind.ONE_OFF, downStart, downStart.plusHours(2),
                null, null, null, null, true));

        AvailabilityResponse inDowntime = availabilityService.check(null,
                OffsetDateTime.parse("2026-01-05T11:00:00-05:00"), null, null, null);
        assertThat(inDowntime.available()).isFalse();
        assertThat(inDowntime.cause()).isEqualTo(Cause.DOWNTIME);

        AvailabilityResponse nightTime = availabilityService.check(null,
                OffsetDateTime.parse("2026-01-05T22:00:00-05:00"), null, null, null);
        assertThat(nightTime.available()).isFalse();
        assertThat(nightTime.cause()).isEqualTo(Cause.NON_WORKING_HOURS);

        AvailabilityResponse fine = availabilityService.check(null,
                OffsetDateTime.parse("2026-01-05T14:00:00-05:00"), null, null, null);
        assertThat(fine.available()).isTrue();

        // next slot from inside the downtime resumes right after it
        NextSlotResponse slot = availabilityService.nextSlot(null,
                OffsetDateTime.parse("2026-01-05T10:30:00-05:00"), 60, null, null, null);
        assertThat(slot.start().toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-01-05T12:00:00-05:00").toInstant());

        List<UnavailableRangeResponse> ranges = availabilityService.unavailableRanges(null,
                OffsetDateTime.parse("2026-01-05T09:00:00-05:00"),
                OffsetDateTime.parse("2026-01-05T17:00:00-05:00"), null, null, null);
        assertThat(ranges).hasSize(1);
        assertThat(ranges.get(0).cause()).isEqualTo(Cause.DOWNTIME);
    }

    @Test
    void featurePolicyOptOutIgnoresHolidays() {
        actAsNewOrg();
        settingsService.upsert(nySettings(null));
        holidayService.importRegion(new HolidayImportRequest("US", null));

        // Christmas 2026-12-25 is a Friday
        OffsetDateTime christmasMorning = OffsetDateTime.parse("2026-12-25T10:00:00-05:00");
        assertThat(availabilityService.check(null, christmasMorning, null, null, null).available())
                .isFalse();

        policyService.upsert(new PolicyRequest("emergency.response", null,
                false, true, false, null, null));
        assertThat(availabilityService.check(null, christmasMorning, "emergency.response", null, null)
                .available()).isTrue();
    }

    @Test
    void workingHoursViewShowsShiftsAndHolidays() {
        actAsNewOrg();
        settingsService.upsert(nySettings(null));
        holidayService.importRegion(new HolidayImportRequest("US", null));

        WorkingHoursResponse monday = availabilityService.workingHours(null, LocalDate.of(2026, 1, 5));
        assertThat(monday.workingDay()).isTrue();
        assertThat(monday.shifts()).hasSize(1);
        assertThat(monday.timezone()).isEqualTo("America/New_York");

        WorkingHoursResponse christmas = availabilityService.workingHours(null, LocalDate.of(2026, 12, 25));
        assertThat(christmas.holiday()).isTrue();
        assertThat(christmas.workingDay()).isFalse();
        assertThat(christmas.shifts()).isEmpty();

        WorkingHoursResponse saturday = availabilityService.workingHours(null, LocalDate.of(2026, 1, 10));
        assertThat(saturday.workingDay()).isFalse();
        assertThat(saturday.shifts()).isEmpty();
    }

    @Test
    void recurringDowntimeBlocksEveryOccurrence() {
        actAsNewOrg();
        settingsService.upsert(nySettings(null));

        com.sclera.applicationplane.calendar.engine.RecurrenceRule weekly =
                new com.sclera.applicationplane.calendar.engine.RecurrenceRule();
        weekly.setFreq(com.sclera.applicationplane.calendar.engine.RecurrenceRule.Freq.WEEKLY);
        weekly.setByDays(java.util.Set.of(DayOfWeek.WEDNESDAY));
        downtimeService.create(new DowntimeRequest(null, null, null,
                DowntimeWindow.Type.MAINTENANCE, "weekly reboot",
                DowntimeWindow.Kind.RECURRING, null, null,
                weekly, LocalDate.of(2026, 1, 7), java.time.LocalTime.of(9, 0), 60, true));

        // two Wednesdays later, 09:30 local is still blocked
        assertThat(availabilityService.check(null,
                OffsetDateTime.parse("2026-01-21T09:30:00-05:00"), null, null, null).available())
                .isFalse();
        assertThat(availabilityService.check(null,
                OffsetDateTime.parse("2026-01-21T10:30:00-05:00"), null, null, null).available())
                .isTrue();
    }

    @Test
    void tenantIsolationBetweenOrgs() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();

        asOrg(orgA, () -> settingsService.upsert(nySettings(null)));
        asOrg(orgB, () -> {
            assertThat(settingsService.list()).isEmpty();
            assertThat(settingsService.effective(null).source())
                    .isEqualTo(EffectiveSettings.SOURCE_BUILT_IN);
        });
        asOrg(orgA, () -> assertThat(settingsService.list()).hasSize(1));
    }

    @Test
    void settingsWriteInvalidatesTheReadCacheImmediately() {
        actAsNewOrg();
        assertThat(settingsService.effective(null).zone().getId()).isEqualTo("UTC");
        settingsService.upsert(nySettings(null));
        assertThat(settingsService.effective(null).zone().getId()).isEqualTo("America/New_York");
    }
}
