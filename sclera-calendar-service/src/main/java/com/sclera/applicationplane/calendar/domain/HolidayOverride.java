package com.sclera.applicationplane.calendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-property adjustment of a holiday: DISABLE it or MOVE it to another date,
 * either for one year or (year NULL) every year.
 */
@Entity
@Table(name = "holiday_override")
public class HolidayOverride {

    public enum Action { DISABLE, MOVE }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "holiday_id", nullable = false)
    private UUID holidayId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Action action;

    @Column(name = "year")
    private Integer year;

    @Column(name = "moved_to")
    private LocalDate movedTo;

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
    public UUID getHolidayId() { return holidayId; }
    public void setHolidayId(UUID holidayId) { this.holidayId = holidayId; }
    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }
    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
    public LocalDate getMovedTo() { return movedTo; }
    public void setMovedTo(LocalDate movedTo) { this.movedTo = movedTo; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
