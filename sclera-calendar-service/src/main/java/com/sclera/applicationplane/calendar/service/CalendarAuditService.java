package com.sclera.applicationplane.calendar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclera.applicationplane.calendar.domain.CalendarAudit;
import com.sclera.applicationplane.calendar.repository.CalendarAuditRepository;
import com.sclera.controlplane.common.security.OrgContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Writes the calendar audit trail in the caller's transaction — a failed
 * mutation must not leave an audit row, and a failed audit write must fail the
 * mutation (config changes here alter downstream scheduling silently).
 */
@Service
public class CalendarAuditService {

    private static final Logger log = LoggerFactory.getLogger(CalendarAuditService.class);

    private final CalendarAuditRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CalendarAuditService(CalendarAuditRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void record(String entityType, UUID entityId, String action, Object snapshot) {
        CalendarAudit audit = new CalendarAudit();
        audit.setId(UUID.randomUUID());
        audit.setOrgId(OrgContext.getOrgId());
        audit.setEntityType(entityType);
        audit.setEntityId(entityId);
        audit.setAction(action);
        audit.setActor(OrgContext.getUserId());
        audit.setAt(OffsetDateTime.now(clock));
        if (snapshot != null) {
            try {
                audit.setSnapshot(objectMapper.writeValueAsString(snapshot));
            } catch (Exception e) {
                // the row (who/what/when) still lands; only the diff payload is lost
                log.warn("Calendar audit snapshot serialization failed for {} {}", entityType, entityId, e);
            }
        }
        repository.save(audit);
    }
}
