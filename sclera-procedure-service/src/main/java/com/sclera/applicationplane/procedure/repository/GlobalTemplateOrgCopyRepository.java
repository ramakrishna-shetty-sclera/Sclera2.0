package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.GlobalTemplateOrgCopy;
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
}
