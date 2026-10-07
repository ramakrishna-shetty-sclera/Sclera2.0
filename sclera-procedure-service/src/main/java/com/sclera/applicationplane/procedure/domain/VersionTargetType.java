package com.sclera.applicationplane.procedure.domain;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * One target type named by one published version — a row of the index that
 * answers "which published procedures apply to this asset class?" without
 * reading a single document. Written once, at publish, and never changed.
 *
 * <p>No row means a version applies to anything; a row means it names this
 * target, among others.
 */
@Entity
@Table(name = "version_target_type")
@IdClass(VersionTargetType.Key.class)
public class VersionTargetType {

    @Id
    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private TargetKind kind;

    /**
     * The key as the version names it, not a link into the vocabulary: that is
     * another service's, and the index has to outlive a decision to retire the key.
     */
    @Id
    @Column(nullable = false, updatable = false, length = 50)
    private String key;

    protected VersionTargetType() {
        // for JPA
    }

    public VersionTargetType(UUID versionId, TargetKind kind, String key) {
        this.versionId = versionId;
        this.kind = kind;
        this.key = key;
    }

    public UUID getVersionId() { return versionId; }
    public TargetKind getKind() { return kind; }
    public String getKey() { return key; }

    /** The three-column primary key: one row per version per kind per key. */
    public static class Key implements Serializable {

        private UUID versionId;
        private TargetKind kind;
        private String key;

        public Key() {
        }

        public Key(UUID versionId, TargetKind kind, String key) {
            this.versionId = versionId;
            this.kind = kind;
            this.key = key;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(versionId, other.versionId)
                    && kind == other.kind
                    && Objects.equals(key, other.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(versionId, kind, key);
        }
    }
}