package com.sclera.applicationplane.calendar.service;

import com.sclera.applicationplane.calendar.domain.DowntimeWindow;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.DowntimeRequest;
import com.sclera.applicationplane.calendar.dto.CalendarDtos.DowntimeResponse;
import com.sclera.applicationplane.calendar.repository.DowntimeWindowRepository;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.exception.ResourceNotFoundException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class DowntimeService {

    private final DowntimeWindowRepository repository;
    private final CalendarCaches caches;
    private final CalendarAuditService audit;
    private final Clock clock;

    public DowntimeService(DowntimeWindowRepository repository, CalendarCaches caches,
                           CalendarAuditService audit, Clock clock) {
        this.repository = repository;
        this.caches = caches;
        this.audit = audit;
        this.clock = clock;
    }

    public DowntimeResponse create(DowntimeRequest request) {
        validate(request);
        DowntimeWindow window = new DowntimeWindow();
        window.setId(UUID.randomUUID());
        window.setOrgId(OrgContext.getOrgId());
        window.setCreatedBy(OrgContext.getUserId());
        window.setCreatedAt(OffsetDateTime.now(clock));
        apply(window, request);
        DowntimeResponse response = toResponse(repository.save(window));
        audit.record("downtime_window", window.getId(), "CREATED", response);
        caches.invalidateOrg(window.getOrgId());
        return response;
    }

    public List<DowntimeResponse> list(UUID propertyId, Boolean active) {
        return repository.findAllByOrgId(OrgContext.getOrgId()).stream()
                .filter(w -> propertyId == null || propertyId.equals(w.getPropertyId()))
                .filter(w -> active == null || w.isActive() == active)
                .sorted(Comparator.comparing(DowntimeWindow::getCreatedAt))
                .map(this::toResponse)
                .toList();
    }

    public DowntimeResponse get(UUID id) {
        return toResponse(getOwned(id));
    }

    public DowntimeResponse update(UUID id, DowntimeRequest request) {
        validate(request);
        DowntimeWindow window = getOwned(id);
        apply(window, request);
        DowntimeResponse response = toResponse(repository.save(window));
        audit.record("downtime_window", id, "UPDATED", response);
        caches.invalidateOrg(window.getOrgId());
        return response;
    }

    public void delete(UUID id) {
        DowntimeWindow window = getOwned(id);
        repository.delete(window);
        audit.record("downtime_window", id, "DELETED", null);
        caches.invalidateOrg(window.getOrgId());
    }

    private void apply(DowntimeWindow window, DowntimeRequest request) {
        window.setPropertyId(request.propertyId());
        if (request.scopeType() != null) window.setScopeType(request.scopeType());
        window.setScopeId(request.scopeId());
        window.setDowntimeType(request.downtimeType());
        window.setReason(request.reason());
        window.setKind(request.kind());
        window.setStartAt(request.startAt());
        window.setEndAt(request.endAt());
        window.setRecurrence(request.recurrence());
        window.setSeriesStart(request.seriesStart());
        window.setLocalStartTime(request.localStartTime());
        window.setDurationMinutes(request.durationMinutes());
        if (request.active() != null) window.setActive(request.active());
        window.setUpdatedAt(OffsetDateTime.now(clock));
    }

    private void validate(DowntimeRequest request) {
        if (request.kind() == DowntimeWindow.Kind.ONE_OFF) {
            if (request.startAt() == null || request.endAt() == null) {
                throw new BusinessRuleException("A one-off downtime window requires startAt and endAt");
            }
            if (!request.startAt().isBefore(request.endAt())) {
                throw new BusinessRuleException("Downtime startAt must be before endAt");
            }
        } else {
            if (request.recurrence() == null || request.seriesStart() == null
                    || request.localStartTime() == null || request.durationMinutes() == null) {
                throw new BusinessRuleException(
                        "A recurring downtime window requires recurrence, seriesStart, localStartTime and durationMinutes");
            }
            try {
                request.recurrence().validate();
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException(e.getMessage());
            }
        }
        if (request.scopeType() != null && request.scopeType() != DowntimeWindow.ScopeType.PROPERTY
                && request.scopeId() == null) {
            throw new BusinessRuleException("A " + request.scopeType() + "-scoped window requires scopeId");
        }
    }

    private DowntimeWindow getOwned(UUID id) {
        return repository.findByIdAndOrgId(id, OrgContext.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Downtime window not found: " + id));
    }

    private DowntimeResponse toResponse(DowntimeWindow w) {
        return new DowntimeResponse(w.getId(), w.getOrgId(), w.getPropertyId(), w.getScopeType(),
                w.getScopeId(), w.getDowntimeType(), w.getReason(), w.getKind(),
                w.getStartAt(), w.getEndAt(), w.getRecurrence(), w.getSeriesStart(),
                w.getLocalStartTime(), w.getDurationMinutes(), w.isActive(),
                w.getCreatedBy(), w.getCreatedAt(), w.getUpdatedAt());
    }
}
