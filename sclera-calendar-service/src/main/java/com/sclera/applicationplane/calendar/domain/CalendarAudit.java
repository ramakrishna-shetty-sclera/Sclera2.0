package com.sclera.applicationplane.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Audit trail for calendar configuration changes — settings, downtime,
 * holidays and policies silently change downstream scheduling behaviour, so
 * every mutation records who did what, with a snapshot of the row after it.
 */
@Entity
@Table(name = "calendar_audit")
public class CalendarAudit {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "entity_type", nullable = false, length = 32)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(nullable = false, length = 16)
    private String action;

    @Column
    private UUID actor;

    @Column(name = "at", nullable = false)
    private OffsetDateTime at;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String snapshot;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public UUID getActor() { return actor; }
    public void setActor(UUID actor) { this.actor = actor; }
    public OffsetDateTime getAt() { return at; }
    public void setAt(OffsetDateTime at) { this.at = at; }
    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
}
