package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One uploaded reference document in the organization's (or one property's)
 * library — the standard extract, the manufacturer's sheet. Upload once, cite
 * from any number of procedures: a version cites this row by id, from inside
 * its own {@code definition_json}, so renaming or deactivating this row
 * reaches every version that cites it rather than leaving stale copies frozen
 * in place.
 *
 * <p>{@link #content} holds the bytes directly, the same way {@code
 * AiDocument} already does — no second service, no Dapr call. {@code
 * property_id} and its row-level-security policy are kept even though {@code
 * ai_document} has neither, because this row is cited across property
 * boundaries by a procedure's own {@code definition_json}, which an AI
 * document never is.
 */
@Entity
@Table(name = "procedure_document")
public class ProcedureDocument {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    /**
     * Owning property (VDMS), or null for a document the whole organization
     * shares. Isolation is absolute: an organization-wide procedure may cite
     * only an organization-wide document, and a property's procedure may cite
     * its own property's documents or the organization's, never another
     * property's.
     */
    @Column(name = "property_id", updatable = false)
    private UUID propertyId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(nullable = false, updatable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;

    public UUID getId() { return id; }

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }

    public UUID getPropertyId() { return propertyId; }
    public void setPropertyId(UUID propertyId) { this.propertyId = propertyId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public UUID getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(UUID uploadedBy) { this.uploadedBy = uploadedBy; }

    public OffsetDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(OffsetDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
}
