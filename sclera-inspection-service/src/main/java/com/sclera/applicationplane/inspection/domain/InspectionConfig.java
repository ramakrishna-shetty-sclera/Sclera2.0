package com.sclera.applicationplane.inspection.domain;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Dummy in-memory inspection configuration (the VDMS "create inspection" form).
 * Held in memory only — resets on restart. Tagging of procedures/assets and the
 * checklist lifecycle come in later steps.
 */
public class InspectionConfig {

    private UUID id;
    private UUID orgId;

    private String name;
    private String code;
    private String description;

    private String assigneeEmail;
    private String secondaryAssigneeEmail;

    private String category;
    private Priority priority;
    private Frequency frequency;
    private Set<Weekday> scheduleDays = EnumSet.noneOf(Weekday.class);

    private boolean bypassScan;
    private boolean enableCheckInOut;
    private boolean enablePoints;
    private boolean mergedView;

    private UUID createdBy;
    private OffsetDateTime createdAt;
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
