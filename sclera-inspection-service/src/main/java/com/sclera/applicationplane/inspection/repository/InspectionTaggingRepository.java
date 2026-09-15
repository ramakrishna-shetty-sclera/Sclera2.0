package com.sclera.applicationplane.inspection.repository;

import com.sclera.applicationplane.inspection.domain.InspectionTagging;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Dummy in-memory tagging store, keyed by inspection-config id. */
@Repository
public class InspectionTaggingRepository {

    private final ConcurrentHashMap<UUID, InspectionTagging> store = new ConcurrentHashMap<>();

    /** Returns the tagging aggregate for a config, creating an empty one on first access. */
    public InspectionTagging getOrCreate(UUID configId) {
        return store.computeIfAbsent(configId, InspectionTagging::new);
    }

    public void deleteByConfigId(UUID configId) {
        store.remove(configId);
    }
}
