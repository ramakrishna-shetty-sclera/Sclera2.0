package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One version of a global template's form — the same shape as
 * {@link ProcedureTemplateVersion}, one level up. {@code definitionJson} is
 * TEXT, kept byte for byte, for the same reason: {@code definitionHash} is
 * taken over those exact bytes.
 *
 * <p>Schema-qualified for the same reason {@link GlobalProcedureTemplate} is:
 * this table lives in {@code public}, and an ordinary tenant-scoped
 * connection's search_path would never find an unqualified reference to it.
 */
@Entity
@Table(name = "global_procedure_template_version", schema = "public")
public class GlobalProcedureTemplateVersion {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "global_template_id", nullable = false, updatable = false)
    private UUID globalTemplateId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VersionState state = VersionState.DRAFT;

    @Column(name = "definition_json", nullable = false, columnDefinition = "text")
    private String definitionJson;

    @Column(name = "definition_hash", nullable = false, length = 64)
    private String definitionHash;

    @Column(name = "change_note", length = 2000)
    private String changeNote;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    public UUID getId() { return id; }

    public UUID getGlobalTemplateId() { return globalTemplateId; }
    public void setGlobalTemplateId(UUID globalTemplateId) { this.globalTemplateId = globalTemplateId; }

    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }

    public VersionState getState() { return state; }
    public void setState(VersionState state) { this.state = state; }

    public String getDefinitionJson() { return definitionJson; }
    public void setDefinitionJson(String definitionJson) { this.definitionJson = definitionJson; }

    public String getDefinitionHash() { return definitionHash; }
    public void setDefinitionHash(String definitionHash) { this.definitionHash = definitionHash; }

    public String getChangeNote() { return changeNote; }
    public void setChangeNote(String changeNote) { this.changeNote = changeNote; }

    public UUID getPublishedBy() { return publishedBy; }
    public void setPublishedBy(UUID publishedBy) { this.publishedBy = publishedBy; }

    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }
}
