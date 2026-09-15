package com.sclera.applicationplane.helper.repository;

import com.sclera.applicationplane.helper.domain.Location;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Dummy in-memory store. Data is per-process and lost on restart. */
@Repository
public class LocationRepository {

    private final Map<UUID, Location> store = new ConcurrentHashMap<>();

    public Location save(Location location) {
        store.put(location.getId(), location);
        return location;
    }

    public Optional<Location> findByIdAndOrgId(UUID id, UUID orgId) {
        Location l = store.get(id);
        return (l != null && l.getOrgId().equals(orgId)) ? Optional.of(l) : Optional.empty();
    }

    public List<Location> findAllByOrgId(UUID orgId) {
        return store.values().stream()
                .filter(l -> l.getOrgId().equals(orgId))
                .toList();
    }

    public boolean existsByParentIdAndOrgId(UUID parentId, UUID orgId) {
        return store.values().stream()
                .anyMatch(l -> l.getOrgId().equals(orgId) && parentId.equals(l.getParentId()));
    }

    public void delete(Location location) {
        store.remove(location.getId());
    }
}
