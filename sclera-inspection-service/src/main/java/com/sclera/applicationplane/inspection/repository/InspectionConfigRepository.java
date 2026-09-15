package com.sclera.applicationplane.inspection.repository;

import com.sclera.applicationplane.inspection.domain.InspectionConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Inspection configurations in the tenant's schema (search_path routing).
 * The org_id predicates are the defense-in-depth cross-check on top of
 * schema isolation.
 */
public interface InspectionConfigRepository extends JpaRepository<InspectionConfig, UUID> {

    Optional<InspectionConfig> findByIdAndOrgId(UUID id, UUID orgId);

    List<InspectionConfig> findAllByOrgIdOrderByCreatedAtDesc(UUID orgId);

    List<InspectionConfig> findAllByOrgIdAndCodeIgnoreCase(UUID orgId, String code);

    default List<InspectionConfig> findAllByOrgId(UUID orgId) {
        return findAllByOrgIdOrderByCreatedAtDesc(orgId);
    }

    /** Code uniqueness per org, ignoring the config being updated. */
    default boolean existsByOrgIdAndCode(UUID orgId, String code, UUID excludeId) {
        return findAllByOrgIdAndCodeIgnoreCase(orgId, code).stream()
                .anyMatch(c -> !c.getId().equals(excludeId));
    }
}
