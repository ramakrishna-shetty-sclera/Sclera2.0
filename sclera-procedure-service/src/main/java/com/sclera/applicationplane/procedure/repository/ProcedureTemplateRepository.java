package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.TargetKind;
import com.sclera.applicationplane.procedure.domain.ProcedureTemplate;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface ProcedureTemplateRepository extends JpaRepository<ProcedureTemplate, UUID> {

    /** Every query is org-scoped — tenants never see each other's templates. */
    Optional<ProcedureTemplate> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * Row lock for anything that mints keys or version numbers, so two
     * concurrent saves can never hand out the same one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ProcedureTemplate t where t.id = :id and t.orgId = :orgId")
    Optional<ProcedureTemplate> lockByIdAndOrgId(UUID id, UUID orgId);

    Page<ProcedureTemplate> findAllByOrgId(UUID orgId, Pageable pageable);

    Page<ProcedureTemplate> findAllByOrgIdAndStatus(UUID orgId, TemplateStatus status, Pageable pageable);

    /**
     * The active procedures whose current published version applies to a target.
     *
     * <p>A version that names no target types applies to anything, so the first
     * branch — no index rows at all — is a match, and it is the common case. An
     * inner join against the index would drop every such procedure. Only the
     * current published version counts: an older version that named the target
     * does not keep a procedure discoverable once a newer one stopped naming it.
     */
    @Query("""
            select t from ProcedureTemplate t
            where t.orgId = :orgId
              and t.status = com.sclera.applicationplane.procedure.domain.TemplateStatus.ACTIVE
              and t.consumerKey = :consumerKey
              and t.currentPublishedVersionId is not null
              and (not exists (select 1 from VersionTargetType a where a.versionId = t.currentPublishedVersionId)
                   or exists (select 1 from VersionTargetType m
                              where m.versionId = t.currentPublishedVersionId
                                and m.kind = :kind and m.key = :key))
            """)
    Page<ProcedureTemplate> findApplicableTo(UUID orgId, String consumerKey, TargetKind kind, String key,
                                             Pageable pageable);

    boolean existsByOrgIdAndNameIgnoreCaseAndStatus(UUID orgId, String name, TemplateStatus status);

    boolean existsByOrgIdAndNameIgnoreCaseAndStatusAndIdNot(UUID orgId, String name, TemplateStatus status, UUID id);
}
