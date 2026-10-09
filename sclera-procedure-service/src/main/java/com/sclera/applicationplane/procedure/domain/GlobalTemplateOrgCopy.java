package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Which organizations have copied which global template, and whether each
 * copy is still tracking the global template's updates.
 *
 * <p>Lives in {@code public}, like the two tables it links — and unlike every
 * other org-scoped table in this service, it has <b>no schema boundary doing
 * the isolating</b>: every organization's requests can reach this same table,
 * since there is no per-tenant copy of it. Every query against it must filter
 * {@code orgId} explicitly in the repository layer; that filter is the only
 * thing protecting the boundary here, not a backstop under a schema wall.
 *
 * <p>{@code linkState = DEFERRED} and {@code deferredVersionNo}/
 * {@code deferredAt} are feature 11's fields to populate — the columns exist
 * now because adding them once is cheap, but nothing in this feature writes
 * them to anything but null.
 */
@Entity
@Table(name = "global_template_org_copy", schema = "public")
@IdClass(GlobalTemplateOrgCopy.Key.class)
public class GlobalTemplateOrgCopy {

    @Id
    @Column(name = "global_template_id", updatable = false)
    private UUID globalTemplateId;

    @Id
    @Column(name = "org_id", updatable = false)
    private UUID orgId;

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Column(name = "applied_version_no", nullable = false)
    private int appliedVersionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_state", nullable = false, length = 20)
    private LinkState linkState = LinkState.LINKED;

    @Column(name = "deferred_version_no")
    private Integer deferredVersionNo;

    @Column(name = "deferred_at")
    private OffsetDateTime deferredAt;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private OffsetDateTime linkedAt;

    protected GlobalTemplateOrgCopy() {
        // for JPA
    }

    public GlobalTemplateOrgCopy(UUID globalTemplateId, UUID orgId, UUID templateId,
                                  int appliedVersionNo, OffsetDateTime linkedAt) {
        this.globalTemplateId = globalTemplateId;
        this.orgId = orgId;
        this.templateId = templateId;
        this.appliedVersionNo = appliedVersionNo;
        this.linkedAt = linkedAt;
    }

    public UUID getGlobalTemplateId() { return globalTemplateId; }
    public UUID getOrgId() { return orgId; }

    public UUID getTemplateId() { return templateId; }

    public int getAppliedVersionNo() { return appliedVersionNo; }
    public void setAppliedVersionNo(int appliedVersionNo) { this.appliedVersionNo = appliedVersionNo; }

    public LinkState getLinkState() { return linkState; }
    public void setLinkState(LinkState linkState) { this.linkState = linkState; }

    public Integer getDeferredVersionNo() { return deferredVersionNo; }
    public void setDeferredVersionNo(Integer deferredVersionNo) { this.deferredVersionNo = deferredVersionNo; }

    public OffsetDateTime getDeferredAt() { return deferredAt; }
    public void setDeferredAt(OffsetDateTime deferredAt) { this.deferredAt = deferredAt; }

    public OffsetDateTime getLinkedAt() { return linkedAt; }

    /** The two-column primary key: one row per organization per global template. */
    public static class Key implements Serializable {

        private UUID globalTemplateId;
        private UUID orgId;

        public Key() {
        }

        public Key(UUID globalTemplateId, UUID orgId) {
            this.globalTemplateId = globalTemplateId;
            this.orgId = orgId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(globalTemplateId, other.globalTemplateId)
                    && Objects.equals(orgId, other.orgId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(globalTemplateId, orgId);
        }
    }
}
