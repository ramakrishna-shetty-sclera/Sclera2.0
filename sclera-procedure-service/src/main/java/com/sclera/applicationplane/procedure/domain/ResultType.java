package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One outcome an answer, question, category or whole inspection can produce —
 * Pass, Fail, Amber, Required, Not applicable, and whatever else an
 * organization defines. Every organization has its own set.
 */
@Entity
@Table(name = "result_type")
public class ResultType {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /**
     * The permanent identifier. Records, answers and definition documents all
     * store this rather than the name, so it is never editable once created —
     * renaming must not orphan anything that already chose this result.
     */
    @Column(nullable = false, updatable = false, length = 50)
    private String key;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 7)
    private String color;

    @Column(length = 500)
    private String description;

    /**
     * Rank where 1 is the MOST severe. Rolling several results into one takes
     * the minimum, so the worst result wins. Dense (1..n) and rewritten
     * wholesale on reorder, which is safe because nothing outside this table
     * stores the number.
     */
    @Column(name = "severity_order", nullable = false)
    private int severityOrder;

    /** Pass and Fail, seeded per tenant. Cannot be deleted or deactivated, but may be renamed and recoloured. */
    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getSeverityOrder() { return severityOrder; }
    public void setSeverityOrder(int severityOrder) { this.severityOrder = severityOrder; }
    public boolean isSystem() { return system; }
    public void setSystem(boolean system) { this.system = system; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
