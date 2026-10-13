package com.sclera.applicationplane.calendar.domain;

import com.sclera.applicationplane.calendar.engine.RecurrenceRule;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A window in which a property (or one of its sub-resources) is unavailable.
 * ONE_OFF windows are absolute instants; RECURRING windows are wall-clock in
 * the property's timezone (localStartTime + durationMinutes on each date the
 * recurrence rule produces) and are expanded at query time.
 */
@Entity
@Table(name = "downtime_window")
public class DowntimeWindow {

    public enum Kind { ONE_OFF, RECURRING }
    public enum Type { MAINTENANCE, BLACKOUT, CLOSURE, OTHER }
    public enum ScopeType { PROPERTY, LOCATION, ASSET }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "property_id")
    private UUID propertyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 16)
    private ScopeType scopeType = ScopeType.PROPERTY;

    @Column(name = "scope_id")
    private UUID scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "downtime_type", nullable = false, length = 16)
    private Type downtimeType;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind kind;

    @Column(name = "start_at")
    private OffsetDateTime startAt;

    @Column(name = "end_at")
    private OffsetDateTime endAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private RecurrenceRule recurrence;

    @Column(name = "series_start")
    private LocalDate seriesStart;

    @Column(name = "local_start_time")
    private LocalTime localStartTime;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public UUID getPropertyId() { return propertyId; }
    public void setPropertyId(UUID propertyId) { this.propertyId = propertyId; }
    public ScopeType getScopeType() { return scopeType; }
    public void setScopeType(ScopeType scopeType) { this.scopeType = scopeType; }
    public UUID getScopeId() { return scopeId; }
    public void setScopeId(UUID scopeId) { this.scopeId = scopeId; }
    public Type getDowntimeType() { return downtimeType; }
    public void setDowntimeType(Type downtimeType) { this.downtimeType = downtimeType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public OffsetDateTime getStartAt() { return startAt; }
    public void setStartAt(OffsetDateTime startAt) { this.startAt = startAt; }
    public OffsetDateTime getEndAt() { return endAt; }
    public void setEndAt(OffsetDateTime endAt) { this.endAt = endAt; }
    public RecurrenceRule getRecurrence() { return recurrence; }
    public void setRecurrence(RecurrenceRule recurrence) { this.recurrence = recurrence; }
    public LocalDate getSeriesStart() { return seriesStart; }
    public void setSeriesStart(LocalDate seriesStart) { this.seriesStart = seriesStart; }
    public LocalTime getLocalStartTime() { return localStartTime; }
    public void setLocalStartTime(LocalTime localStartTime) { this.localStartTime = localStartTime; }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
