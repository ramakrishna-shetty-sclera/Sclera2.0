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
 * One result-type key named by one published version — a row of the index
 * that answers "does any published version still use this result type?"
 * without reading a single document. Written once, at publish, and never
 * changed.
 */
@Entity
@Table(name = "version_result_type_ref")
@IdClass(VersionResultTypeRef.Key.class)
public class VersionResultTypeRef {

    @Id
    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    /**
     * The key as the version names it, not a link to the result type row: the
     * index has to outlive a decision to retire the type, which is when it
     * matters most.
     */
    @Id
    @Column(name = "result_type_key", nullable = false, updatable = false, length = 50)
    private String resultTypeKey;

    protected VersionResultTypeRef() {
        // for JPA
    }

    public VersionResultTypeRef(UUID versionId, String resultTypeKey) {
        this.versionId = versionId;
        this.resultTypeKey = resultTypeKey;
    }

    public UUID getVersionId() { return versionId; }
    public String getResultTypeKey() { return resultTypeKey; }

    /** The two-column primary key: one row per version per key. */
    public static class Key implements Serializable {

        private UUID versionId;
        private String resultTypeKey;

        public Key() {
        }

        public Key(UUID versionId, String resultTypeKey) {
            this.versionId = versionId;
            this.resultTypeKey = resultTypeKey;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(versionId, other.versionId)
                    && Objects.equals(resultTypeKey, other.resultTypeKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(versionId, resultTypeKey);
        }
    }
}
