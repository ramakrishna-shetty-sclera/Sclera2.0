package com.sclera.applicationplane.helper.repository;

import com.sclera.applicationplane.helper.domain.Asset;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Dummy in-memory store. Data is per-process and lost on restart. */
@Repository
public class AssetRepository {

    private final Map<UUID, Asset> store = new ConcurrentHashMap<>();

    public Asset save(Asset asset) {
        store.put(asset.getId(), asset);
        return asset;
    }

    public Optional<Asset> findByIdAndOrgId(UUID id, UUID orgId) {
        Asset a = store.get(id);
        return (a != null && a.getOrgId().equals(orgId)) ? Optional.of(a) : Optional.empty();
    }

    public List<Asset> findAllByOrgId(UUID orgId) {
        return store.values().stream()
                .filter(a -> a.getOrgId().equals(orgId))
                .toList();
    }

    public boolean existsByLocationIdAndOrgId(UUID locationId, UUID orgId) {
        return store.values().stream()
                .anyMatch(a -> a.getOrgId().equals(orgId) && locationId.equals(a.getLocationId()));
    }

    public void delete(Asset asset) {
        store.remove(asset.getId());
    }
}
