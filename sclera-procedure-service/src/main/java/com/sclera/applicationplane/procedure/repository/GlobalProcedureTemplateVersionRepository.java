package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import com.sclera.applicationplane.procedure.domain.VersionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GlobalProcedureTemplateVersionRepository extends JpaRepository<GlobalProcedureTemplateVersion, UUID> {

    Optional<GlobalProcedureTemplateVersion> findByGlobalTemplateIdAndVersionNo(UUID globalTemplateId, int versionNo);

    List<GlobalProcedureTemplateVersion> findAllByGlobalTemplateIdOrderByVersionNoAsc(UUID globalTemplateId);

    Optional<GlobalProcedureTemplateVersion> findByGlobalTemplateIdAndState(UUID globalTemplateId, VersionState state);

    Optional<GlobalProcedureTemplateVersion> findByGlobalTemplateIdAndStateAndDefinitionHash(
            UUID globalTemplateId, VersionState state, String definitionHash);

    /**
     * The highest version number ever minted, not merely the current one —
     * republishing a draft that matched older content repoints
     * {@code current_published_version_id} backwards without deleting the
     * higher version number that was discarded, so the next draft must not
     * reuse it.
     */
    @Query("select coalesce(max(v.versionNo), 0) from GlobalProcedureTemplateVersion v "
            + "where v.globalTemplateId = :globalTemplateId")
    int maxVersionNo(UUID globalTemplateId);
}
