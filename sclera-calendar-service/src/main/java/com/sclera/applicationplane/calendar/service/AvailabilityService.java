package com.sclera.applicationplane.calendar.service;

import com.sclera.applicationplane.calendar.config.CalendarProperties;
import com.sclera.applicationplane.calendar.domain.DowntimeWindow;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.AvailabilityResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.NextSlotResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.ResolvedShift;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.UnavailableRangeResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.WorkingHoursResponse;
import com.sclera.applicationplane.calendar.engine.AvailabilityCalculator;
import com.sclera.applicationplane.calendar.engine.AvailabilityCalculator.Cause;
import com.sclera.applicationplane.calendar.engine.AvailabilityCalculator.PropertyCalendar;
import com.sclera.applicationplane.calendar.engine.AvailabilityCalculator.Range;
import com.sclera.applicationplane.calendar.engine.HolidayResolver;
import com.sclera.applicationplane.calendar.engine.HolidayResolver.ResolvedHoliday;
import com.sclera.applicationplane.calendar.repository.DowntimeWindowRepository;
import com.sclera.applicationplane.calendar.repository.HolidayOverrideRepository;
import com.sclera.applicationplane.calendar.repository.HolidayRepository;
import com.sclera.applicationplane.calendar.service.CalendarCaches.RuleBundle;
import com.sclera.applicationplane.calendar.service.FeaturePolicyService.EffectivePolicy;
import com.sclera.applicationplane.calendar.tenancy.TenantContext;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The query side: builds a {@link PropertyCalendar} snapshot (effective
 * settings + policy-filtered holidays and downtime) and delegates the interval
 * math to {@link AvailabilityCalculator}. Results come back in the property's
 * local offset so consumers can display them without re-resolving the zone.
 */
@Service
@Transactional(readOnly = true)
public class AvailabilityService {

    private final CalendarSettingsService settingsService;
    private final FeaturePolicyService policyService;
    private final HolidayRepository holidayRepository;
    private final HolidayOverrideRepository overrideRepository;
    private final DowntimeWindowRepository downtimeRepository;
    private final CalendarCaches caches;
    private final CalendarProperties properties;

    public AvailabilityService(CalendarSettingsService settingsService,
                               FeaturePolicyService policyService,
                               HolidayRepository holidayRepository,
                               HolidayOverrideRepository overrideRepository,
                               DowntimeWindowRepository downtimeRepository,
                               CalendarCaches caches,
                               CalendarProperties properties) {
        this.settingsService = settingsService;
        this.policyService = policyService;
        this.holidayRepository = holidayRepository;
        this.overrideRepository = overrideRepository;
        this.downtimeRepository = downtimeRepository;
        this.caches = caches;
        this.properties = properties;
    }

    public AvailabilityResponse check(UUID propertyId, OffsetDateTime at, String featureCode,
                                      DowntimeWindow.ScopeType scopeType, UUID scopeId) {
        EffectiveSettings settings = settingsService.effective(propertyId);
        LocalDate date = at.toInstant().atZone(settings.zone()).toLocalDate();
        PropertyCalendar cal = buildCalendar(settings, propertyId, featureCode, scopeType, scopeId,
                date.minusDays(1), date.plusDays(1));
        Optional<Cause> cause = AvailabilityCalculator.blockingCause(cal, at.toInstant());
        String detail = cause.filter(c -> c == Cause.HOLIDAY)
                .map(c -> cal.holidayOn(date))
                .map(ResolvedHoliday::name)
                .orElse(null);
        return new AvailabilityResponse(at, cause.isEmpty(), cause.orElse(null), detail);
    }

    public NextSlotResponse nextSlot(UUID propertyId, OffsetDateTime from, int durationMinutes,
                                     String featureCode, DowntimeWindow.ScopeType scopeType, UUID scopeId) {
        if (durationMinutes < 1) {
            throw new BusinessRuleException("durationMinutes must be >= 1");
        }
        EffectiveSettings settings = settingsService.effective(propertyId);
        LocalDate fromDate = from.toInstant().atZone(settings.zone()).toLocalDate();
        int horizon = properties.getNextSlotHorizonDays();
        PropertyCalendar cal = buildCalendar(settings, propertyId, featureCode, scopeType, scopeId,
                fromDate.minusDays(1), fromDate.plusDays(horizon + 1));
        return AvailabilityCalculator
                .nextAvailableSlot(cal, from.toInstant(), Duration.ofMinutes(durationMinutes), horizon)
                .map(r -> new NextSlotResponse(local(r.start(), settings.zone()), local(r.end(), settings.zone())))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No available slot of " + durationMinutes + " minutes within " + horizon + " days"));
    }

    public List<UnavailableRangeResponse> unavailableRanges(UUID propertyId, OffsetDateTime from,
                                                            OffsetDateTime to, String featureCode,
                                                            DowntimeWindow.ScopeType scopeType, UUID scopeId) {
        if (!from.isBefore(to)) {
            throw new BusinessRuleException("'from' must be before 'to'");
        }
        if (ChronoUnit.DAYS.between(from, to) > properties.getMaxRangeDays()) {
            throw new BusinessRuleException(
                    "Range too large — max " + properties.getMaxRangeDays() + " days");
        }
        EffectiveSettings settings = settingsService.effective(propertyId);
        LocalDate fromDate = from.toInstant().atZone(settings.zone()).toLocalDate();
        LocalDate toDate = to.toInstant().atZone(settings.zone()).toLocalDate();
        PropertyCalendar cal = buildCalendar(settings, propertyId, featureCode, scopeType, scopeId,
                fromDate.minusDays(1), toDate.plusDays(1));
        return AvailabilityCalculator.unavailableRanges(cal, from.toInstant(), to.toInstant()).stream()
                .map(r -> new UnavailableRangeResponse(
                        local(r.start(), settings.zone()), local(r.end(), settings.zone()),
                        r.cause(), r.detail()))
                .toList();
    }

    public WorkingHoursResponse workingHours(UUID propertyId, LocalDate date) {
        EffectiveSettings settings = settingsService.effective(propertyId);
        // no feature filter here: this is the raw rule view, holidays always shown
        PropertyCalendar cal = buildCalendar(settings, propertyId, null, null, null,
                date.minusDays(1), date.plusDays(1));
        ResolvedHoliday holiday = cal.holidayOn(date);
        List<ResolvedShift> shifts = AvailabilityCalculator.workingSegments(cal, date).stream()
                .map(r -> new ResolvedShift(local(r.start(), settings.zone()), local(r.end(), settings.zone())))
                .toList();
        boolean workingDay = !settings.workingHours().getOrDefault(date.getDayOfWeek(), List.of()).isEmpty();
        return new WorkingHoursResponse(date, settings.zone().getId(),
                workingDay && holiday == null, holiday != null,
                holiday != null ? holiday.name() : null, shifts);
    }

    // ── snapshot assembly ───────────────────────────────────────────────────

    private PropertyCalendar buildCalendar(EffectiveSettings settings, UUID propertyId,
                                           String featureCode, DowntimeWindow.ScopeType scopeType,
                                           UUID scopeId, LocalDate from, LocalDate to) {
        UUID orgId = TenantContext.currentOrgId();
        EffectivePolicy policy = policyService.effective(featureCode, propertyId);
        RuleBundle bundle = caches.bundle(orgId, () -> new RuleBundle(
                holidayRepository.findAllByOrgIdAndEnabledTrue(orgId),
                overrideRepository.findAllByOrgId(orgId),
                downtimeRepository.findAllByOrgIdAndActiveTrue(orgId)));

        Map<LocalDate, ResolvedHoliday> holidays = new HashMap<>();
        if (policy.respectHolidays()) {
            List<com.sclera.applicationplane.calendar.domain.Holiday> applicable =
                    bundle.holidays().stream()
                            .filter(h -> h.getPropertyId() == null
                                    || (propertyId != null && propertyId.equals(h.getPropertyId())))
                            .filter(h -> policy.appliesTo(h.getCategory()))
                            .toList();
            List<com.sclera.applicationplane.calendar.domain.HolidayOverride> overrides =
                    propertyId == null ? List.of()
                            : bundle.overrides().stream()
                                    .filter(o -> propertyId.equals(o.getPropertyId()))
                                    .toList();
            for (ResolvedHoliday r : HolidayResolver.resolve(applicable, overrides, from, to)) {
                holidays.putIfAbsent(r.date(), r);
            }
        }

        List<DowntimeWindow> downtime = policy.respectDowntime()
                ? bundle.downtime().stream()
                        .filter(w -> w.getPropertyId() == null
                                || (propertyId != null && propertyId.equals(w.getPropertyId())))
                        .filter(w -> policy.appliesTo(w.getDowntimeType()))
                        .filter(w -> appliesToScope(w, scopeType, scopeId))
                        .toList()
                : List.of();

        return new PropertyCalendar(settings.zone(), settings.workingHours(),
                policy.respectWorkingHours(), holidays, downtime);
    }

    /**
     * PROPERTY-scoped windows block everything at the property; a sub-resource
     * window only blocks queries about that same sub-resource.
     */
    private boolean appliesToScope(DowntimeWindow w, DowntimeWindow.ScopeType scopeType, UUID scopeId) {
        if (w.getScopeType() == DowntimeWindow.ScopeType.PROPERTY) {
            return true;
        }
        return w.getScopeType() == scopeType && Objects.equals(w.getScopeId(), scopeId);
    }

    private OffsetDateTime local(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toOffsetDateTime();
    }
}
