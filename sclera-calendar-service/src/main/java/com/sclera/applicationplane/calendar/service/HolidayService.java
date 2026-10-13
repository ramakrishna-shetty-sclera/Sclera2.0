package com.sclera.applicationplane.calendar.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclera.applicationplane.calendar.domain.Holiday;
import com.sclera.applicationplane.calendar.domain.HolidayOverride;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.HolidayImportRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.HolidayRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.HolidayResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.OverrideRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.OverrideResponse;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.ResolvedHolidayResponse;
import com.sclera.applicationplane.calendar.engine.HolidayResolver;
import com.sclera.applicationplane.calendar.repository.HolidayOverrideRepository;
import com.sclera.applicationplane.calendar.repository.HolidayRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class HolidayService {

    private final HolidayRepository repository;
    private final HolidayOverrideRepository overrideRepository;
    private final CalendarCaches caches;
    private final CalendarAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HolidayService(HolidayRepository repository, HolidayOverrideRepository overrideRepository,
                          CalendarCaches caches, CalendarAuditService audit,
                          ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.overrideRepository = overrideRepository;
        this.caches = caches;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public HolidayResponse create(HolidayRequest request) {
        Holiday holiday = new Holiday();
        holiday.setId(UUID.randomUUID());
        holiday.setOrgId(OrgContext.getOrgId());
        holiday.setCreatedBy(OrgContext.getUserId());
        holiday.setCreatedAt(OffsetDateTime.now(clock));
        holiday.setSource("MANUAL");
        apply(holiday, request);
        HolidayResponse response = toResponse(repository.save(holiday));
        audit.record("holiday", holiday.getId(), "CREATED", response);
        caches.invalidateOrg(holiday.getOrgId());
        return response;
    }

    public List<HolidayResponse> list(UUID propertyId) {
        return repository.findAllByOrgId(OrgContext.getOrgId()).stream()
                .filter(h -> propertyId == null
                        || h.getPropertyId() == null || propertyId.equals(h.getPropertyId()))
                .sorted(Comparator.comparing(Holiday::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toResponse)
                .toList();
    }

    public HolidayResponse get(UUID id) {
        return toResponse(getOwned(id));
    }

    public HolidayResponse update(UUID id, HolidayRequest request) {
        Holiday holiday = getOwned(id);
        apply(holiday, request);
        HolidayResponse response = toResponse(repository.save(holiday));
        audit.record("holiday", id, "UPDATED", response);
        caches.invalidateOrg(holiday.getOrgId());
        return response;
    }

    public void delete(UUID id) {
        Holiday holiday = getOwned(id);
        overrideRepository.deleteAll(overrideRepository.findAllByHolidayId(id));
        repository.delete(holiday);
        audit.record("holiday", id, "DELETED", null);
        caches.invalidateOrg(holiday.getOrgId());
    }

    // ── overrides ───────────────────────────────────────────────────────────

    public OverrideResponse addOverride(UUID holidayId, OverrideRequest request) {
        Holiday holiday = getOwned(holidayId);
        if (request.action() == HolidayOverride.Action.MOVE && request.movedTo() == null) {
            throw new BusinessRuleException("A MOVE override requires movedTo");
        }
        HolidayOverride override = new HolidayOverride();
        override.setId(UUID.randomUUID());
        override.setOrgId(holiday.getOrgId());
        override.setPropertyId(request.propertyId());
        override.setHolidayId(holidayId);
        override.setAction(request.action());
        override.setYear(request.year());
        override.setMovedTo(request.movedTo());
        override.setCreatedBy(OrgContext.getUserId());
        OffsetDateTime now = OffsetDateTime.now(clock);
        override.setCreatedAt(now);
        override.setUpdatedAt(now);
        OverrideResponse response = toResponse(overrideRepository.save(override));
        audit.record("holiday_override", override.getId(), "CREATED", response);
        caches.invalidateOrg(holiday.getOrgId());
        return response;
    }

    public void deleteOverride(UUID overrideId) {
        HolidayOverride override = overrideRepository.findByIdAndOrgId(overrideId, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Holiday override not found: " + overrideId));
        overrideRepository.delete(override);
        audit.record("holiday_override", overrideId, "DELETED", null);
        caches.invalidateOrg(override.getOrgId());
    }

    public List<OverrideResponse> listOverrides(UUID holidayId) {
        getOwned(holidayId);
        return overrideRepository.findAllByHolidayId(holidayId).stream()
                .map(this::toResponse)
                .toList();
    }

    // ── resolution + import ─────────────────────────────────────────────────

    /** Concrete holiday dates for a property (org-wide + property rows, after overrides). */
    @Transactional(readOnly = true)
    public List<ResolvedHolidayResponse> resolved(UUID propertyId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessRuleException("'to' must not be before 'from'");
        }
        List<Holiday> applicable = repository.findAllByOrgIdAndEnabledTrue(OrgContext.getOrgId()).stream()
                .filter(h -> h.getPropertyId() == null
                        || (propertyId != null && propertyId.equals(h.getPropertyId())))
                .toList();
        List<HolidayOverride> overrides = propertyId == null
                ? List.of()
                : overrideRepository.findAllByOrgIdAndPropertyId(OrgContext.getOrgId(), propertyId);
        return HolidayResolver.resolve(applicable, overrides, from, to).stream()
                .map(r -> new ResolvedHolidayResponse(r.date(), r.holidayId(), r.name(), r.category()))
                .toList();
    }

    /**
     * Imports a bundled regional pack (classpath holidays/{REGION}.json),
     * replacing a previous import of the same region for the same scope.
     * Imported rows are ordinary holidays afterwards: editable, disable-able,
     * override-able per property.
     */
    public List<HolidayResponse> importRegion(HolidayImportRequest request) {
        String region = request.region().toUpperCase(Locale.ROOT);
        if (!region.matches("[A-Z]{2}")) {
            throw new BusinessRuleException("Region must be a two-letter code (e.g. US, GB, IN)");
        }
        ClassPathResource resource = new ClassPathResource("holidays/" + region + ".json");
        if (!resource.exists()) {
            throw new ResourceNotFoundException("No bundled holiday pack for region: " + region);
        }
        List<Map<String, Object>> defs;
        try {
            Map<String, Object> pack = objectMapper.readValue(resource.getInputStream(),
                    new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> holidays = (List<Map<String, Object>>) pack.get("holidays");
            defs = holidays;
        } catch (Exception e) {
            throw new IllegalStateException("Bundled holiday pack " + region + " is unreadable", e);
        }

        UUID orgId = OrgContext.getOrgId();
        List<Holiday> stale = repository.findAllByOrgIdAndSource(orgId, region).stream()
                .filter(h -> java.util.Objects.equals(h.getPropertyId(), request.propertyId()))
                .toList();
        stale.forEach(h -> overrideRepository.deleteAll(overrideRepository.findAllByHolidayId(h.getId())));
        repository.deleteAll(stale);

        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Holiday> imported = new ArrayList<>();
        for (Map<String, Object> def : defs) {
            Holiday h = objectMapper.convertValue(def, Holiday.class);
            h.setId(UUID.randomUUID());
            h.setOrgId(orgId);
            h.setPropertyId(request.propertyId());
            h.setSource(region);
            h.setEnabled(true);
            h.setCreatedBy(OrgContext.getUserId());
            h.setCreatedAt(now);
            h.setUpdatedAt(now);
            if (h.getCategory() == null) {
                h.setCategory("PUBLIC");
            }
            try {
                HolidayResolver.validateDefinition(h);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Invalid definition in pack " + region + ": " + e.getMessage());
            }
            imported.add(h);
        }
        repository.saveAll(imported);
        audit.record("holiday", orgId, "IMPORTED",
                Map.of("region", region, "count", imported.size(),
                        "propertyId", String.valueOf(request.propertyId())));
        caches.invalidateOrg(orgId);
        return imported.stream().map(this::toResponse).toList();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void apply(Holiday holiday, HolidayRequest request) {
        holiday.setPropertyId(request.propertyId());
        holiday.setName(request.name());
        if (request.category() != null) holiday.setCategory(request.category());
        holiday.setDateType(request.dateType());
        holiday.setMonth(request.month());
        holiday.setDayOfMonth(request.dayOfMonth());
        holiday.setWeekOrdinal(request.weekOrdinal());
        holiday.setDayOfWeek(request.dayOfWeek());
        holiday.setOnDate(request.onDate());
        if (request.enabled() != null) holiday.setEnabled(request.enabled());
        holiday.setUpdatedAt(OffsetDateTime.now(clock));
        try {
            HolidayResolver.validateDefinition(holiday);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(e.getMessage());
        }
    }

    private Holiday getOwned(UUID id) {
        return repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Holiday not found: " + id));
    }

    private HolidayResponse toResponse(Holiday h) {
        return new HolidayResponse(h.getId(), h.getOrgId(), h.getPropertyId(), h.getName(),
                h.getCategory(), h.getDateType(), h.getMonth(), h.getDayOfMonth(),
                h.getWeekOrdinal(), h.getDayOfWeek(), h.getOnDate(), h.getSource(), h.isEnabled(),
                h.getCreatedBy(), h.getCreatedAt(), h.getUpdatedAt());
    }

    private OverrideResponse toResponse(HolidayOverride o) {
        return new OverrideResponse(o.getId(), o.getOrgId(), o.getPropertyId(), o.getHolidayId(),
                o.getAction(), o.getYear(), o.getMovedTo(),
                o.getCreatedBy(), o.getCreatedAt(), o.getUpdatedAt());
    }
}
