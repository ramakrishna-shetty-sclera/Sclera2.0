package com.sclera.applicationplane.calendar.service;

import com.sclera.applicationplane.calendar.domain.DowntimeWindow;
import com.sclera.applicationplane.calendar.domain.FeatureCalendarPolicy;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.PolicyRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.PolicyResponse;
import com.sclera.applicationplane.calendar.repository.FeatureCalendarPolicyRepository;
import com.sclera.applicationplane.calendar.tenancy.TenantContext;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class FeaturePolicyService {

    /** Resolved policy — a feature with no row respects everything. */
    public record EffectivePolicy(boolean respectWorkingHours, boolean respectDowntime,
                                  boolean respectHolidays, Set<DowntimeWindow.Type> downtimeTypes,
                                  Set<String> holidayCategories) {

        public static final EffectivePolicy RESPECT_ALL =
                new EffectivePolicy(true, true, true, Set.of(), Set.of());

        public boolean appliesTo(DowntimeWindow.Type type) {
            return downtimeTypes.isEmpty() || downtimeTypes.contains(type);
        }

        public boolean appliesTo(String holidayCategory) {
            return holidayCategories.isEmpty() || holidayCategories.contains(holidayCategory);
        }
    }

    private final FeatureCalendarPolicyRepository repository;
    private final CalendarAuditService audit;
    private final Clock clock;

    public FeaturePolicyService(FeatureCalendarPolicyRepository repository,
                                CalendarAuditService audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    public List<PolicyResponse> list() {
        return repository.findAllByOrgId(OrgContext.getOrgId()).stream()
                .sorted(Comparator.comparing(FeatureCalendarPolicy::getFeatureCode))
                .map(this::toResponse)
                .toList();
    }

    /** Upsert keyed by (featureCode, propertyId). */
    public PolicyResponse upsert(PolicyRequest request) {
        UUID orgId = OrgContext.getOrgId();
        FeatureCalendarPolicy policy = (request.propertyId() == null
                ? repository.findByOrgIdAndFeatureCodeAndPropertyIdIsNull(orgId, request.featureCode())
                : repository.findByOrgIdAndFeatureCodeAndPropertyId(orgId, request.featureCode(), request.propertyId()))
                .orElse(null);
        boolean created = policy == null;
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (created) {
            policy = new FeatureCalendarPolicy();
            policy.setId(UUID.randomUUID());
            policy.setOrgId(orgId);
            policy.setFeatureCode(request.featureCode());
            policy.setPropertyId(request.propertyId());
            policy.setCreatedBy(OrgContext.getUserId());
            policy.setCreatedAt(now);
        }
        if (request.respectWorkingHours() != null) policy.setRespectWorkingHours(request.respectWorkingHours());
        if (request.respectDowntime() != null) policy.setRespectDowntime(request.respectDowntime());
        if (request.respectHolidays() != null) policy.setRespectHolidays(request.respectHolidays());
        policy.setDowntimeTypes(request.downtimeTypes() == null ? null
                : request.downtimeTypes().stream().map(Enum::name).collect(Collectors.joining(",")));
        policy.setHolidayCategories(request.holidayCategories() == null ? null
                : String.join(",", request.holidayCategories()));
        policy.setUpdatedAt(now);
        PolicyResponse response = toResponse(repository.save(policy));
        audit.record("feature_calendar_policy", policy.getId(), created ? "CREATED" : "UPDATED", response);
        return response;
    }

    /** Lookup: (feature, property) row → (feature, org-wide) row → respect-all. */
    @Transactional(readOnly = true)
    public EffectivePolicy effective(String featureCode, UUID propertyId) {
        if (featureCode == null || featureCode.isBlank()) {
            return EffectivePolicy.RESPECT_ALL;
        }
        UUID orgId = TenantContext.currentOrgId();
        FeatureCalendarPolicy policy = null;
        if (propertyId != null) {
            policy = repository.findByOrgIdAndFeatureCodeAndPropertyId(orgId, featureCode, propertyId)
                    .orElse(null);
        }
        if (policy == null) {
            policy = repository.findByOrgIdAndFeatureCodeAndPropertyIdIsNull(orgId, featureCode)
                    .orElse(null);
        }
        if (policy == null) {
            return EffectivePolicy.RESPECT_ALL;
        }
        Set<DowntimeWindow.Type> types = FeatureCalendarPolicy.csvToSet(policy.getDowntimeTypes()).stream()
                .map(DowntimeWindow.Type::valueOf)
                .collect(Collectors.toSet());
        return new EffectivePolicy(policy.isRespectWorkingHours(), policy.isRespectDowntime(),
                policy.isRespectHolidays(), types,
                FeatureCalendarPolicy.csvToSet(policy.getHolidayCategories()));
    }

    private PolicyResponse toResponse(FeatureCalendarPolicy p) {
        return new PolicyResponse(p.getId(), p.getOrgId(), p.getFeatureCode(), p.getPropertyId(),
                p.isRespectWorkingHours(), p.isRespectDowntime(), p.isRespectHolidays(),
                List.copyOf(FeatureCalendarPolicy.csvToSet(p.getDowntimeTypes())),
                List.copyOf(FeatureCalendarPolicy.csvToSet(p.getHolidayCategories())),
                p.getCreatedBy(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
