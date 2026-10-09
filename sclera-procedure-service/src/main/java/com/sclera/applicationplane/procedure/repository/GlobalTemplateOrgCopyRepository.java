package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.GlobalTemplateOrgCopy;
import com.sclera.applicationplane.procedure.domain.LinkState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * No schema boundary isolates this table (see {@link GlobalTemplateOrgCopy}),
 * so every method here takes {@code orgId} explicitly and filters on it —
 * that filter is the only thing protecting the boundary.
 */
public interface GlobalTemplateOrgCopyRepository
        extends JpaRepository<GlobalTemplateOrgCopy, GlobalTemplateOrgCopy.Key> {

    Optional<GlobalTemplateOrgCopy> findByGlobalTemplateIdAndOrgId(UUID globalTemplateId, UUID orgId);

    Optional<GlobalTemplateOrgCopy> findByTemplateIdAndOrgId(UUID templateId, UUID orgId);

    /**
     * How many organizations currently track this global template's updates
     * — the broadcast-visible count a publish reports, not org-scoped
     * because the whole point is to count across every organization.
     */
    long countByGlobalTemplateIdAndLinkState(UUID globalTemplateId, LinkState linkState);
}
