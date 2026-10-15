package com.sclera.applicationplane.procedure.repository;

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

    boolean existsByOrgIdAndNameIgnoreCaseAndStatus(UUID orgId, String name, TemplateStatus status);

    boolean existsByOrgIdAndNameIgnoreCaseAndStatusAndIdNot(UUID orgId, String name, TemplateStatus status, UUID id);
}
