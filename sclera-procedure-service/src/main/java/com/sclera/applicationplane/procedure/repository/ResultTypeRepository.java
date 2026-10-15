package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.ResultType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResultTypeRepository extends JpaRepository<ResultType, UUID> {

    /** Every query is org-scoped — tenants never see each other's result types. */
    Optional<ResultType> findByIdAndOrgId(UUID id, UUID orgId);

    /** Ordered worst-first, since severity 1 is the most severe. */
    List<ResultType> findAllByOrgIdOrderBySeverityOrderAsc(UUID orgId);

    List<ResultType> findAllByOrgIdAndActiveOrderBySeverityOrderAsc(UUID orgId, boolean active);

    boolean existsByOrgIdAndKeyIgnoreCase(UUID orgId, String key);
}
