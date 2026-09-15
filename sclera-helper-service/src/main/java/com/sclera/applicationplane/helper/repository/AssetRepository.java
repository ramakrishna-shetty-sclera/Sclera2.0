package com.sclera.applicationplane.helper.repository;

import com.sclera.applicationplane.helper.domain.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Assets in the tenant's schema (search_path routing). The org_id predicates
 * are the defense-in-depth cross-check on top of schema isolation.
 */
public interface AssetRepository extends JpaRepository<Asset, UUID> {

    Optional<Asset> findByIdAndOrgId(UUID id, UUID orgId);

    List<Asset> findAllByOrgId(UUID orgId);

    boolean existsByLocationIdAndOrgId(UUID locationId, UUID orgId);
}
