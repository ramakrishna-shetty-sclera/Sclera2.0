package com.sclera.applicationplane.inspection.repository;

import com.sclera.applicationplane.inspection.domain.Inspection;
import com.sclera.applicationplane.inspection.domain.InspectionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InspectionRepository extends JpaRepository<Inspection, UUID> {

    /** Every query is org-scoped — tenants never see each other's inspections. */
    Optional<Inspection> findByIdAndOrgId(UUID id, UUID orgId);

    Page<Inspection> findAllByOrgId(UUID orgId, Pageable pageable);

    Page<Inspection> findAllByOrgIdAndStatus(UUID orgId, InspectionStatus status, Pageable pageable);

    Page<Inspection> findAllByOrgIdAndTemplateId(UUID orgId, UUID templateId, Pageable pageable);
}
