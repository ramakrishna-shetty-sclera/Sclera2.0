package com.sclera.applicationplane.inspection.service;

import com.sclera.applicationplane.inspection.domain.InspectionConfig;
import com.sclera.applicationplane.inspection.dto.InspectionConfigRequest;
import com.sclera.applicationplane.inspection.dto.InspectionConfigResponse;
import com.sclera.applicationplane.inspection.repository.InspectionConfigRepository;
import com.sclera.applicationplane.inspection.repository.InspectionTaggingRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.exception.ValidationException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Service
public class InspectionConfigService {

    private final InspectionConfigRepository repository;
    private final InspectionTaggingRepository taggingRepository;

    public InspectionConfigService(InspectionConfigRepository repository,
                                   InspectionTaggingRepository taggingRepository) {
        this.repository = repository;
        this.taggingRepository = taggingRepository;
    }

    public InspectionConfigResponse create(InspectionConfigRequest request) {
        UUID orgId = OrgContext.getOrgId();
        validate(request, orgId, null);

        InspectionConfig config = new InspectionConfig();
        config.setId(UUID.randomUUID());
        config.setOrgId(orgId);
        config.setCreatedBy(OrgContext.getUserId());
        apply(config, request);
        OffsetDateTime now = OffsetDateTime.now();
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        return toResponse(repository.save(config));
    }

    public List<InspectionConfigResponse> list() {
        return repository.findAllByOrgId(OrgContext.getOrgId()).stream()
                .map(this::toResponse)
                .toList();
    }

    public InspectionConfigResponse get(UUID id) {
        return toResponse(getOwned(id));
    }

    public InspectionConfigResponse update(UUID id, InspectionConfigRequest request) {
        InspectionConfig config = getOwned(id);
        validate(request, config.getOrgId(), id);
        apply(config, request);
        config.setUpdatedAt(OffsetDateTime.now());
        return toResponse(repository.save(config));
    }

    public void delete(UUID id) {
        InspectionConfig config = getOwned(id);
        repository.delete(config);
        taggingRepository.deleteByConfigId(id);
    }

    private InspectionConfig getOwned(UUID id) {
        return repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Inspection configuration not found: " + id));
    }

    private void validate(InspectionConfigRequest r, UUID orgId, UUID excludeId) {
        String secondary = trimToNull(r.secondaryAssigneeEmail());
        if (secondary != null && secondary.equalsIgnoreCase(r.assigneeEmail().trim())) {
            throw new ValidationException("Secondary assignee must be different from the primary assignee");
        }
        String code = trimToNull(r.code());
        if (code != null && repository.existsByOrgIdAndCode(orgId, code, excludeId)) {
            throw new BusinessRuleException("Code already in use: " + code);
        }
    }

    private void apply(InspectionConfig c, InspectionConfigRequest r) {
        c.setName(r.name().trim());
        c.setCode(trimToNull(r.code()));
        c.setDescription(trimToNull(r.description()));
        c.setAssigneeEmail(r.assigneeEmail().trim());
        c.setSecondaryAssigneeEmail(trimToNull(r.secondaryAssigneeEmail()));
        c.setCategory(r.category() == null || r.category().isBlank() ? "Generic" : r.category().trim());
        c.setPriority(r.priority());
        c.setFrequency(r.frequency());
        c.setScheduleDays(EnumSet.copyOf(r.scheduleDays()));
        c.setBypassScan(r.bypassScan());
        c.setEnableCheckInOut(r.enableCheckInOut());
        c.setEnablePoints(r.enablePoints());
        c.setMergedView(r.mergedView());
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private InspectionConfigResponse toResponse(InspectionConfig c) {
        return new InspectionConfigResponse(
                c.getId(), c.getOrgId(), c.getName(), c.getCode(), c.getDescription(),
                c.getAssigneeEmail(), c.getSecondaryAssigneeEmail(), c.getCategory(),
                c.getPriority(), c.getFrequency(), c.getScheduleDays(),
                c.isBypassScan(), c.isEnableCheckInOut(), c.isEnablePoints(), c.isMergedView(),
                c.getCreatedBy(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
