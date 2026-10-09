package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One consumer's last report of which version of a procedure it is using.
 *
 * <p>A report and not a pointer: the consumer says "this configuration is on
 * version V of template T" and this row holds that until it reports again. It
 * is the answer to "before you publish v4, which configurations are still on
 * v3?", so {@link #versionId} is whatever was last reported and not the current
 * version.
 *
 * <p>Identified by its own id and made unique by
 * {@code (consumerKey, consumerRefId, templateId)}, which is what makes a
 * repeated report an update in place rather than a second row. That is a
 * different shape from the {@code version_*} index tables, which have no id of
 * their own and are keyed by what they index.
 */
@Entity
@Table(name = "procedure_usage")
public class ProcedureUsage {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "consumer_key", nullable = false, updatable = false, length = 50)
    private String consumerKey;

    /** The consumer's own id for whatever uses the procedure. Opaque here. */
    @Column(name = "consumer_ref_id", nullable = false, updatable = false, length = 100)
    private String consumerRefId;

    /** Which target type the consumer bound it to, if any. Absent when it bound none. */
    @Column(name = "target_type_key", length = 50)
    private String targetTypeKey;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    protected ProcedureUsage() {
        // for JPA
    }

    public ProcedureUsage(UUID templateId, UUID versionId, String consumerKey, String consumerRefId,
                          String targetTypeKey) {
        this.templateId = templateId;
        this.versionId = versionId;
        this.consumerKey = consumerKey;
        this.consumerRefId = consumerRefId;
        this.targetTypeKey = targetTypeKey;
        this.recordedAt = OffsetDateTime.now();
    }

    /**
     * Records a fresh report for the same consumer and template: the version and
     * target type it is on now, and when it said so. The identity never changes.
     */
    public void reportAgain(UUID newVersionId, String newTargetTypeKey) {
        this.versionId = newVersionId;
        this.targetTypeKey = newTargetTypeKey;
        this.recordedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getTemplateId() { return templateId; }
    public UUID getVersionId() { return versionId; }
    public String getConsumerKey() { return consumerKey; }
    public String getConsumerRefId() { return consumerRefId; }
    public String getTargetTypeKey() { return targetTypeKey; }
    public OffsetDateTime getRecordedAt() { return recordedAt; }
}
