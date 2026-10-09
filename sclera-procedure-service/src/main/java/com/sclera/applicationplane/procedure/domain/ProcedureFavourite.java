package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One user's star on one procedure — the library's favourites (guide §9).
 * A join table and nothing more: no row-level security, since a favourite
 * belongs to a user, not a property, and should follow them regardless of
 * which property they are standing in.
 */
@Entity
@Table(name = "procedure_favourite")
@IdClass(ProcedureFavourite.Key.class)
public class ProcedureFavourite {

    @Id
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Id
    @Column(name = "template_id", updatable = false)
    private UUID templateId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ProcedureFavourite() {
        // for JPA
    }

    public ProcedureFavourite(UUID userId, UUID templateId) {
        this.userId = userId;
        this.templateId = templateId;
    }

    public UUID getUserId() { return userId; }
    public UUID getTemplateId() { return templateId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    /** The two-column primary key: one star per user per template. */
    public static class Key implements Serializable {

        private UUID userId;
        private UUID templateId;

        public Key() {
        }

        public Key(UUID userId, UUID templateId) {
            this.userId = userId;
            this.templateId = templateId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(userId, other.userId)
                    && Objects.equals(templateId, other.templateId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, templateId);
        }
    }
}
