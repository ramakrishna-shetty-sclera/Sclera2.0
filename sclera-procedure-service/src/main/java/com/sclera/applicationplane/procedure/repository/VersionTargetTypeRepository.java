package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.VersionTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * What published versions apply to. Lives in each organization's schema, so a
 * key is only ever compared with its own organization's versions. The discovery
 * query that reads it comes with the endpoint that needs it.
 */
public interface VersionTargetTypeRepository
        extends JpaRepository<VersionTargetType, VersionTargetType.Key> {
}