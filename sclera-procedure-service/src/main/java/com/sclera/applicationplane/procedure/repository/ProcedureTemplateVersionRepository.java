package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.ProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.VersionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Versions are only ever reached through a template the caller already loaded
 * org-scoped, so these queries key on templateId alone.
 */
public interface ProcedureTemplateVersionRepository extends JpaRepository<ProcedureTemplateVersion, UUID> {

    Optional<ProcedureTemplateVersion> findByTemplateIdAndState(UUID templateId, VersionState state);

    Optional<ProcedureTemplateVersion> findByTemplateIdAndVersionNo(UUID templateId, int versionNo);

    Optional<ProcedureTemplateVersion> findByTemplateIdAndStateAndDefinitionHash(
            UUID templateId, VersionState state, String definitionHash);

    List<ProcedureTemplateVersion> findAllByTemplateIdOrderByVersionNoDesc(UUID templateId);

    /** Drafts for a page of templates in one query, for list responses. */
    List<ProcedureTemplateVersion> findAllByTemplateIdInAndState(Collection<UUID> templateIds, VersionState state);

    @Query("select coalesce(max(v.versionNo), 0) from ProcedureTemplateVersion v where v.templateId = :templateId")
    int maxVersionNo(UUID templateId);
}
