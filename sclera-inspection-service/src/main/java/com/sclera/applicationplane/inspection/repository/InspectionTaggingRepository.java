package com.sclera.applicationplane.inspection.repository;

import com.sclera.applicationplane.inspection.domain.InspectionTagging;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Tagging aggregates in the tenant's schema, keyed by inspection-config id. */
public interface InspectionTaggingRepository extends JpaRepository<InspectionTagging, UUID> {

    /** Returns the tagging aggregate for a config, creating an empty one on first access. */
    default InspectionTagging getOrCreate(UUID configId) {
        return findById(configId).orElseGet(() -> save(new InspectionTagging(configId)));
    }

    default void deleteByConfigId(UUID configId) {
        deleteById(configId);
    }
}
