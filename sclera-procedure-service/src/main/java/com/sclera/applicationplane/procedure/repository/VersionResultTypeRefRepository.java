package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.VersionResultTypeRef;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Which result types published versions use. Lives in each organization's
 * schema, so a key is only ever compared with its own organization's versions.
 */
public interface VersionResultTypeRefRepository
        extends JpaRepository<VersionResultTypeRef, VersionResultTypeRef.Key> {

    /**
     * How many published versions name this result type. The primary key allows
     * one row per version per key, so counting rows counts versions.
     */
    long countByResultTypeKey(String resultTypeKey);
}
