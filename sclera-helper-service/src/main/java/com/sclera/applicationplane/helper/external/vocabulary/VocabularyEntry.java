package com.sclera.applicationplane.helper.external.vocabulary;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * One entry in one of the four vocabulary tables, which all have the same
 * shape. Read-only for now: no setters, so nothing in this service can change
 * a key once it is stored, and the {@code key} — the identity other services
 * keep — is never reused or renamed.
 *
 * <p>The organization is the schema, so there is no org_id here.
 */
@MappedSuperclass
public abstract class VocabularyEntry {

    @Id
    @Column(nullable = false, updatable = false, length = 50)
    private String key;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active = true;

    public String getKey() { return key; }
    public String getName() { return name; }
    public int getDisplayOrder() { return displayOrder; }
    public boolean isActive() { return active; }
}