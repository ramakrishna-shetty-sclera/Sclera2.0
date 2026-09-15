package com.sclera.applicationplane.inspection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Inspection configuration (the VDMS "create inspection" form). Persisted in
 * the tenant's schema (schema-per-tenant); org_id is a defense-in-depth
 * cross-check only.
 */
@Entity
@Table(name = "inspection_config")
public class InspectionConfig {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 50)
    private String code;

    @Column(length = 2000)
    private String description;

    @Column(name = "assignee_email", nullable = false, length = 255)
    private String assigneeEmail;

    @Column(name = "secondary_assignee_email", length = 255)
    private String secondaryAssigneeEmail;

    @Column(length = 100)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Frequency frequency;

    @Convert(converter = WeekdaysConverter.class)
    @Column(name = "schedule_days", nullable = false, length = 100)
    private Set<Weekday> scheduleDays = EnumSet.noneOf(Weekday.class);

    @Column(name = "bypass_scan", nullable = false)
    private boolean bypassScan;

    @Column(name = "enable_check_in_out", nullable = false)
    private boolean enableCheckInOut;

    @Column(name = "enable_points", nullable = false)
    private boolean enablePoints;

    @Column(name = "merged_view", nullable = false)
    private boolean mergedView;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAssigneeEmail() { return assigneeEmail; }
    public void setAssigneeEmail(String assigneeEmail) { this.assigneeEmail = assigneeEmail; }
    public String getSecondaryAssigneeEmail() { return secondaryAssigneeEmail; }
    public void setSecondaryAssigneeEmail(String secondaryAssigneeEmail) { this.secondaryAssigneeEmail = secondaryAssigneeEmail; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }
    public Frequency getFrequency() { return frequency; }
    public void setFrequency(Frequency frequency) { this.frequency = frequency; }
    public Set<Weekday> getScheduleDays() { return scheduleDays; }
    public void setScheduleDays(Set<Weekday> scheduleDays) { this.scheduleDays = scheduleDays; }
    public boolean isBypassScan() { return bypassScan; }
    public void setBypassScan(boolean bypassScan) { this.bypassScan = bypassScan; }
    public boolean isEnableCheckInOut() { return enableCheckInOut; }
    public void setEnableCheckInOut(boolean enableCheckInOut) { this.enableCheckInOut = enableCheckInOut; }
    public boolean isEnablePoints() { return enablePoints; }
    public void setEnablePoints(boolean enablePoints) { this.enablePoints = enablePoints; }
    public boolean isMergedView() { return mergedView; }
    public void setMergedView(boolean mergedView) { this.mergedView = mergedView; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
