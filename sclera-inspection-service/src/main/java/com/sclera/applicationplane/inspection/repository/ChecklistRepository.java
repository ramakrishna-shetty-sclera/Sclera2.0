package com.sclera.applicationplane.inspection.repository;

import com.sclera.applicationplane.inspection.domain.Checklist;
import com.sclera.applicationplane.inspection.domain.ChecklistSource;
import com.sclera.applicationplane.inspection.domain.ChecklistStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Checklists in the tenant's schema (search_path routing). */
public interface ChecklistRepository extends JpaRepository<Checklist, UUID> {

    Optional<Checklist> findByIdAndOrgId(UUID id, UUID orgId);

    List<Checklist> findAllByOrgIdOrderByCreatedAtDesc(UUID orgId);

    /**
     * Optional filters applied in-code over the org's rows — avoids the
     * combinatorial derived-query explosion; volumes are per-tenant small.
     */
    default List<Checklist> findAllByOrgId(UUID orgId, UUID configId, ChecklistStatus status,
                                           ChecklistSource source) {
        return findAllByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .filter(c -> configId == null || configId.equals(c.getConfigId()))
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> source == null || c.getSource() == source)
                .toList();
    }
}
