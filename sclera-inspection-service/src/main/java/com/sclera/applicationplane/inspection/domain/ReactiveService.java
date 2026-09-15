package com.sclera.applicationplane.inspection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Reactive Service: a checklist (procedure) associated with a location,
 * reachable through a QR token. Scanning the QR resolves the token and raises
 * a request — a Checklist with source=REACTIVE_SERVICE. Persisted in the
 * tenant's schema.
 */
@Entity
@Table(name = "reactive_service")
public class ReactiveService {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false, length = 220)
    private String name;

    @Column(name = "procedure_id", nullable = false)
    private UUID procedureId;

    @Column(name = "procedure_name", nullable = false, length = 200)
    private String procedureName;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "location_name", nullable = false, length = 200)
    private String locationName;

    /** Opaque token embedded in the QR code / scan URL. */
    @Column(name = "qr_token", nullable = false, length = 64, unique = true)
    private String qrToken;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public UUID getProcedureId() { return procedureId; }
    public void setProcedureId(UUID procedureId) { this.procedureId = procedureId; }
    public String getProcedureName() { return procedureName; }
    public void setProcedureName(String procedureName) { this.procedureName = procedureName; }
    public UUID getLocationId() { return locationId; }
    public void setLocationId(UUID locationId) { this.locationId = locationId; }
    public String getLocationName() { return locationName; }
    public void setLocationName(String locationName) { this.locationName = locationName; }
    public String getQrToken() { return qrToken; }
    public void setQrToken(String qrToken) { this.qrToken = qrToken; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
