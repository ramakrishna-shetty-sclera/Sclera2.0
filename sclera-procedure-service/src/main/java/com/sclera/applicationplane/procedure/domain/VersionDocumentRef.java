package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * One document cited by one published version — a row of the index that
 * answers "is this document still cited by any published version?" without
 * reading a single document's {@code definition_json}. Written once, at
 * publish, and never changed.
 */
@Entity
@Table(name = "version_document_ref")
@IdClass(VersionDocumentRef.Key.class)
public class VersionDocumentRef {

    @Id
    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    /**
     * No foreign key to {@code procedure_document}: the guard this index
     * serves is what protects the document, not the database.
     */
    @Id
    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    protected VersionDocumentRef() {
        // for JPA
    }

    public VersionDocumentRef(UUID versionId, UUID documentId) {
        this.versionId = versionId;
        this.documentId = documentId;
    }

    public UUID getVersionId() { return versionId; }
    public UUID getDocumentId() { return documentId; }

    /** The two-column primary key: one row per version per document. */
    public static class Key implements Serializable {

        private UUID versionId;
        private UUID documentId;

        public Key() {
        }

        public Key(UUID versionId, UUID documentId) {
            this.versionId = versionId;
            this.documentId = documentId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(versionId, other.versionId)
                    && Objects.equals(documentId, other.documentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(versionId, documentId);
        }
    }
}
