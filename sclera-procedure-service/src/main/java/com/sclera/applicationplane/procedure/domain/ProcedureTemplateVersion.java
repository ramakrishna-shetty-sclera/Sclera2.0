package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One version of a procedure's form. The whole form is {@code definitionJson},
 * stored as the exact canonical bytes that {@code definitionHash} was computed
 * over. A DRAFT is mutable; once published the row is never modified again, so
 * anything that pinned its id always renders the questions that were asked.
 */
@Entity
@Table(name = "procedure_template_version")
public class ProcedureTemplateVersion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VersionState state = VersionState.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private VersionOrigin origin = VersionOrigin.AUTHORED;

    @Column(name = "definition_json", nullable = false, columnDefinition = "text")
    private String definitionJson;

    @Column(name = "definition_hash", nullable = false, length = 64)
    private String definitionHash;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "change_note", length = 2000)
    private String changeNote;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** Optimistic lock on this row — not a version of the form. */
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @PrePersist
    void onCreate() {
        if (firstSeenAt == null) {
            firstSeenAt = OffsetDateTime.now();
        }
    }

    public boolean isDraft() {
        return state == VersionState.DRAFT;
    }

    /** Freezes this version. The content and hash must already be final. */
    public void markPublished(UUID userId, OffsetDateTime at) {
        this.state = VersionState.PUBLISHED;
        this.publishedBy = userId;
        this.publishedAt = at;
    }

    public UUID getId() { return id; }

    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID templateId) { this.templateId = templateId; }

    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }

    public VersionState getState() { return state; }

    public VersionOrigin getOrigin() { return origin; }
    public void setOrigin(VersionOrigin origin) { this.origin = origin; }

    public String getDefinitionJson() { return definitionJson; }
    public String getDefinitionHash() { return definitionHash; }

    /** Sets the canonical bytes and their hash together, so they cannot drift apart. */
    public void setDefinition(String canonicalJson, String hash) {
        this.definitionJson = canonicalJson;
        this.definitionHash = hash;
    }

    public OffsetDateTime getFirstSeenAt() { return firstSeenAt; }

    public String getChangeNote() { return changeNote; }
    public void setChangeNote(String changeNote) { this.changeNote = changeNote; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public UUID getPublishedBy() { return publishedBy; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public long getRowVersion() { return rowVersion; }
}
