package com.sclera.applicationplane.calendar.service;

import com.sclera.applicationplane.calendar.domain.CalendarSettings;
import com.sclera.applicationplane.calendar.domain.ShiftDef;
import com.sclera.applicationplane.calendar.domain.WeekNumbering;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.DateInfo;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.EffectiveSettingsResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.SettingsRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.SettingsResponse;
import com.sclera.applicationplane.calendar.repository.CalendarSettingsRepository;
import com.sclera.applicationplane.calendar.tenancy.TenantContext;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class CalendarSettingsService {

    private final CalendarSettingsRepository repository;
    private final CalendarCaches caches;
    private final CalendarAuditService audit;
    private final Clock clock;

    public CalendarSettingsService(CalendarSettingsRepository repository, CalendarCaches caches,
                                   CalendarAuditService audit, Clock clock) {
        this.repository = repository;
        this.caches = caches;
        this.audit = audit;
        this.clock = clock;
    }

    public List<SettingsResponse> list() {
        return repository.findAllByOrgId(OrgContext.getOrgId()).stream()
                .sorted(Comparator.comparing(CalendarSettings::getCreatedAt))
                .map(this::toResponse)
                .toList();
    }

    /** Upsert keyed by propertyId (null = the org-wide default row). */
    public SettingsResponse upsert(SettingsRequest request) {
        UUID orgId = OrgContext.getOrgId();
        validate(request);
        CalendarSettings settings = (request.propertyId() == null
                ? repository.findByOrgIdAndPropertyIdIsNull(orgId)
                : repository.findByOrgIdAndPropertyId(orgId, request.propertyId()))
                .orElse(null);
        boolean created = settings == null;
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (created) {
            settings = new CalendarSettings();
            settings.setId(UUID.randomUUID());
            settings.setOrgId(orgId);
            settings.setPropertyId(request.propertyId());
            settings.setCreatedBy(OrgContext.getUserId());
            settings.setCreatedAt(now);
        }
        settings.setTimezone(request.timezone());
        if (request.use24HourClock() != null) settings.setUse24HourClock(request.use24HourClock());
        if (request.dateFormatPattern() != null) settings.setDateFormatPattern(request.dateFormatPattern());
        if (request.firstDayOfWeek() != null) settings.setFirstDayOfWeek(request.firstDayOfWeek());
        if (request.weekNumbering() != null) settings.setWeekNumbering(request.weekNumbering());
        if (request.fiscalYearStartMonth() != null) settings.setFiscalYearStartMonth(request.fiscalYearStartMonth());
        if (request.fiscalYearStartDay() != null) settings.setFiscalYearStartDay(request.fiscalYearStartDay());
        settings.setWorkingHours(request.workingHours());
        settings.setUpdatedAt(now);
        SettingsResponse response = toResponse(repository.save(settings));
        audit.record("calendar_settings", settings.getId(), created ? "CREATED" : "UPDATED", response);
        caches.invalidateOrg(orgId);
        return response;
    }

    public void delete(UUID id) {
        CalendarSettings settings = repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Calendar settings not found: " + id));
        repository.delete(settings);
        audit.record("calendar_settings", id, "DELETED", null);
        caches.invalidateOrg(settings.getOrgId());
    }

    /**
     * Resolution: property row → org default row → built-in defaults. Cached.
     * Org comes from TenantContext so HMAC-guarded /internal callers (no JWT,
     * orgId via TenantContext.runAs) resolve the same way as JWT requests.
     */
    @Transactional(readOnly = true)
    public EffectiveSettings effective(UUID propertyId) {
        UUID orgId = TenantContext.currentOrgId();
        return caches.settings(orgId, propertyId, () -> loadEffective(orgId, propertyId));
    }

    @Transactional(readOnly = true)
    public EffectiveSettingsResponse effectiveResponse(UUID propertyId, LocalDate date) {
        EffectiveSettings s = effective(propertyId);
        DateInfo info = null;
        if (date != null) {
            boolean workingDay = !s.workingHours().getOrDefault(date.getDayOfWeek(), List.of()).isEmpty();
            info = new DateInfo(date, weekNumber(date, s), fiscalYear(date, s), workingDay, false, null);
        }
        return new EffectiveSettingsResponse(propertyId, s.source(), s.zone().getId(),
                s.use24HourClock(), s.dateFormatPattern(), s.firstDayOfWeek(), s.weekNumbering(),
                s.fiscalYearStartMonth(), s.fiscalYearStartDay(), s.workingHours(), info);
    }

    /** ISO: week 1 contains the first Thursday. US: week 1 contains Jan 1. */
    public int weekNumber(LocalDate date, EffectiveSettings settings) {
        WeekFields fields = settings.weekNumbering() == WeekNumbering.ISO
                ? WeekFields.ISO
                : WeekFields.of(settings.firstDayOfWeek(), 1);
        return date.get(fields.weekOfWeekBasedYear());
    }

    /** Fiscal year labelled by its starting calendar year. */
    public int fiscalYear(LocalDate date, EffectiveSettings settings) {
        MonthDay start = MonthDay.of(settings.fiscalYearStartMonth(), settings.fiscalYearStartDay());
        LocalDate fyStartThisYear = start.atYear(date.getYear());
        return date.isBefore(fyStartThisYear) ? date.getYear() - 1 : date.getYear();
    }

    private EffectiveSettings loadEffective(UUID orgId, UUID propertyId) {
        CalendarSettings row = null;
        String source = EffectiveSettings.SOURCE_BUILT_IN;
        if (propertyId != null) {
            row = repository.findByOrgIdAndPropertyId(orgId, propertyId).orElse(null);
            if (row != null) {
                source = EffectiveSettings.SOURCE_PROPERTY;
            }
        }
        if (row == null) {
            row = repository.findByOrgIdAndPropertyIdIsNull(orgId).orElse(null);
            if (row != null) {
                source = EffectiveSettings.SOURCE_ORG_DEFAULT;
            }
        }
        if (row == null) {
            return EffectiveSettings.builtIn(propertyId);
        }
        return new EffectiveSettings(propertyId, source, ZoneId.of(row.getTimezone()),
                row.isUse24HourClock(), row.getDateFormatPattern(), row.getFirstDayOfWeek(),
                row.getWeekNumbering(), row.getFiscalYearStartMonth(), row.getFiscalYearStartDay(),
                row.getWorkingHours());
    }

    private void validate(SettingsRequest request) {
        try {
            ZoneId.of(request.timezone());
        } catch (Exception e) {
            throw new BusinessRuleException("Unknown IANA timezone: " + request.timezone());
        }
        int month = request.fiscalYearStartMonth() != null ? request.fiscalYearStartMonth() : 1;
        int day = request.fiscalYearStartDay() != null ? request.fiscalYearStartDay() : 1;
        if (day > Month.of(month).maxLength() || (month == 2 && day == 29)) {
            throw new BusinessRuleException("Fiscal year cannot start on " + month + "/" + day);
        }
        for (Map.Entry<DayOfWeek, List<ShiftDef>> e : request.workingHours().entrySet()) {
            for (ShiftDef shift : e.getValue()) {
                try {
                    shift.validate();
                } catch (IllegalArgumentException ex) {
                    throw new BusinessRuleException(e.getKey() + ": " + ex.getMessage());
                }
            }
        }
    }

    private SettingsResponse toResponse(CalendarSettings s) {
        return new SettingsResponse(s.getId(), s.getOrgId(), s.getPropertyId(), s.getTimezone(),
                s.isUse24HourClock(), s.getDateFormatPattern(), s.getFirstDayOfWeek(),
                s.getWeekNumbering(), s.getCalendarSystem(), s.getFiscalYearStartMonth(),
                s.getFiscalYearStartDay(), s.getWorkingHours(),
                s.getCreatedBy(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
