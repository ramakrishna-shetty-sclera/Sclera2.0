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
 * Identity of a procedure in Sclera's own shared library — visible to every
 * organization, not scoped to any one of them. Holds no content; the form
 * lives in {@link GlobalProcedureTemplateVersion}, exactly as
 * {@link ProcedureTemplate}'s content lives in {@link ProcedureTemplateVersion}.
 *
 * <p>Lives in the {@code public} schema, not any tenant schema — there is no
 * organization to scope it to. A per-request connection's search_path is
 * pinned to the caller's own tenant schema with no {@code public} fallback
 * (see {@code SchemaMultiTenantConnectionProvider}), so this entity must be
 * schema-qualified explicitly or an ordinary tenant-scoped request would
 * never find it.
 */
@Entity
@Table(name = "global_procedure_template", schema = "public")
public class GlobalProcedureTemplate {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "consumer_key", length = 50)
    private String consumerKey;

    /** The moving pointer: always the form in force now. Null until first publish. */
    @Column(name = "current_published_version_id")
    private UUID currentPublishedVersionId;

    /** Counter behind every question key, carried forward into an org's imported copy. */
    @Column(name = "key_seq", nullable = false)
    private int keySeq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TemplateStatus status = TemplateStatus.ACTIVE;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getConsumerKey() { return consumerKey; }
    public void setConsumerKey(String consumerKey) { this.consumerKey = consumerKey; }

    public UUID getCurrentPublishedVersionId() { return currentPublishedVersionId; }
    public void setCurrentPublishedVersionId(UUID id) { this.currentPublishedVersionId = id; }

    public int getKeySeq() { return keySeq; }
    public void setKeySeq(int keySeq) { this.keySeq = keySeq; }

    public TemplateStatus getStatus() { return status; }
    public void setStatus(TemplateStatus status) { this.status = status; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
