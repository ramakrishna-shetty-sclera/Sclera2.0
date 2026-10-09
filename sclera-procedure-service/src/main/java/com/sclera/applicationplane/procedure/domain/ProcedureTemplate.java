package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Identity of a procedure: name, consumer and status. Holds no content — the
 * form lives in {@link ProcedureTemplateVersion}. One row per template, however
 * many times it is edited.
 */
@Entity
@Table(name = "procedure_template")
public class ProcedureTemplate {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /**
     * Owning property (VDMS), or null for a procedure the whole organization
     * shares. Not a foreign key: properties belong to another service, so this
     * holds the id and nothing joins on it.
     *
     * Reading it back is rarely necessary — row-level security already filters
     * on it, so a query cannot return a property this caller may not see.
     */
    @Column(name = "property_id", updatable = false)
    private UUID propertyId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "consumer_key", nullable = false, length = 50)
    private String consumerKey = "INSPECTION";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TemplateStatus status = TemplateStatus.ACTIVE;

    /** The moving pointer: always the form in force now. Null until first publish. */
    @Column(name = "current_published_version_id")
    private UUID currentPublishedVersionId;

    /** Counter behind every category and question key; never decremented. */
    @Column(name = "key_seq", nullable = false)
    private int keySeq;

    @Column(name = "global_template_id")
    private UUID globalTemplateId;

    /**
     * The organization-wide {@code procedure_template} row (same org,
     * {@code propertyId IS NULL}) this property-level fork came from. Null
     * for everything else — an org-wide template, a hand-authored
     * property-scoped one, or one linked to a global template instead.
     *
     * <p>Never both this and {@link #globalTemplateId} on the same row:
     * importing happens at organization level and sets the latter; forking
     * happens at property level, onto a different row, and sets this one.
     */
    @Column(name = "forked_from_template_id")
    private UUID forkedFromTemplateId;

    /** The parent's version number this fork last applied. Null unless {@link #forkedFromTemplateId} is set. */
    @Column(name = "applied_version_no")
    private Integer appliedVersionNo;

    /**
     * Reused unmodified from {@code GlobalTemplateOrgCopy} — the three
     * states mean the same thing one level down. Null unless
     * {@link #forkedFromTemplateId} is set.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "link_state", length = 20)
    private LinkState linkState;

    @Column(name = "deferred_version_no")
    private Integer deferredVersionNo;

    @Column(name = "deferred_at")
    private OffsetDateTime deferredAt;

    @Column(name = "legacy_id", length = 100)
    private String legacyId;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Returns the next unused key number. Callers must hold a lock on this row. */
    public int nextKeyNumber() {
        return ++keySeq;
    }

    public UUID getId() { return id; }

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }

    public UUID getPropertyId() { return propertyId; }
    public void setPropertyId(UUID propertyId) { this.propertyId = propertyId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getConsumerKey() { return consumerKey; }
    public void setConsumerKey(String consumerKey) { this.consumerKey = consumerKey; }

    public TemplateStatus getStatus() { return status; }
    public void setStatus(TemplateStatus status) { this.status = status; }

    public UUID getCurrentPublishedVersionId() { return currentPublishedVersionId; }
    public void setCurrentPublishedVersionId(UUID id) { this.currentPublishedVersionId = id; }

    public int getKeySeq() { return keySeq; }
    public void setKeySeq(int keySeq) { this.keySeq = keySeq; }

    public UUID getGlobalTemplateId() { return globalTemplateId; }
    public void setGlobalTemplateId(UUID globalTemplateId) { this.globalTemplateId = globalTemplateId; }

    public UUID getForkedFromTemplateId() { return forkedFromTemplateId; }
    public void setForkedFromTemplateId(UUID forkedFromTemplateId) { this.forkedFromTemplateId = forkedFromTemplateId; }

    public Integer getAppliedVersionNo() { return appliedVersionNo; }
    public void setAppliedVersionNo(Integer appliedVersionNo) { this.appliedVersionNo = appliedVersionNo; }

    public LinkState getLinkState() { return linkState; }
    public void setLinkState(LinkState linkState) { this.linkState = linkState; }

    public Integer getDeferredVersionNo() { return deferredVersionNo; }
    public void setDeferredVersionNo(Integer deferredVersionNo) { this.deferredVersionNo = deferredVersionNo; }

    public OffsetDateTime getDeferredAt() { return deferredAt; }
    public void setDeferredAt(OffsetDateTime deferredAt) { this.deferredAt = deferredAt; }

    public String getLegacyId() { return legacyId; }
    public void setLegacyId(String legacyId) { this.legacyId = legacyId; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
