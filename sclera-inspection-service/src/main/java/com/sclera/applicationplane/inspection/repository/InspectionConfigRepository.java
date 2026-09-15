package com.sclera.applicationplane.inspection.repository;

import com.sclera.applicationplane.inspection.domain.InspectionConfig;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dummy in-memory store for inspection configurations. Deliberately NOT a JPA
 * entity — data is per-process and lost on restart.
 */
@Repository
public class InspectionConfigRepository {

    private final ConcurrentHashMap<UUID, InspectionConfig> store = new ConcurrentHashMap<>();

    public InspectionConfig save(InspectionConfig config) {
        store.put(config.getId(), config);
        return config;
    }

    public Optional<InspectionConfig> findByIdAndOrgId(UUID id, UUID orgId) {
        InspectionConfig c = store.get(id);
        return (c != null && c.getOrgId().equals(orgId)) ? Optional.of(c) : Optional.empty();
    }

    public List<InspectionConfig> findAllByOrgId(UUID orgId) {
        return store.values().stream()
                .filter(c -> c.getOrgId().equals(orgId))
                .sorted(Comparator.comparing(InspectionConfig::getCreatedAt).reversed())
                .toList();
    }

    /** Code uniqueness per org, ignoring the config being updated. */
    public boolean existsByOrgIdAndCode(UUID orgId, String code, UUID excludeId) {
        return store.values().stream().anyMatch(c ->
                c.getOrgId().equals(orgId)
                        && code.equalsIgnoreCase(c.getCode())
                        && !c.getId().equals(excludeId));
    }

    public void delete(InspectionConfig config) {
        store.remove(config.getId());
    }
}
