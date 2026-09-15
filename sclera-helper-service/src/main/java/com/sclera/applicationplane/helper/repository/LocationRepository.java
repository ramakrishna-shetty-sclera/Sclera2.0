package com.sclera.applicationplane.helper.repository;

import com.sclera.applicationplane.helper.domain.Location;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Locations in the tenant's schema (search_path routing). The org_id
 * predicates are the defense-in-depth cross-check on top of schema isolation.
 */
public interface LocationRepository extends JpaRepository<Location, UUID> {

    Optional<Location> findByIdAndOrgId(UUID id, UUID orgId);

    List<Location> findAllByOrgId(UUID orgId);

    boolean existsByParentIdAndOrgId(UUID parentId, UUID orgId);
}
