package com.sclera.applicationplane.inspection.repository;

import com.sclera.applicationplane.inspection.domain.Checklist;
import com.sclera.applicationplane.inspection.domain.ChecklistStatus;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Dummy in-memory checklist store. Data is per-process and lost on restart. */
@Repository
public class ChecklistRepository {

    private final ConcurrentHashMap<UUID, Checklist> store = new ConcurrentHashMap<>();

    public Checklist save(Checklist checklist) {
        store.put(checklist.getId(), checklist);
        return checklist;
    }

    public Optional<Checklist> findByIdAndOrgId(UUID id, UUID orgId) {
        Checklist c = store.get(id);
        return (c != null && c.getOrgId().equals(orgId)) ? Optional.of(c) : Optional.empty();
    }

    public List<Checklist> findAllByOrgId(UUID orgId, UUID configId, ChecklistStatus status) {
        return store.values().stream()
                .filter(c -> c.getOrgId().equals(orgId))
                .filter(c -> configId == null || configId.equals(c.getConfigId()))
                .filter(c -> status == null || c.getStatus() == status)
                .sorted(Comparator.comparing(Checklist::getCreatedAt).reversed())
                .toList();
    }

    public void delete(Checklist checklist) {
        store.remove(checklist.getId());
    }
}
