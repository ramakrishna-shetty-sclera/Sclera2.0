package com.sclera.applicationplane.inspection.repository;

import com.sclera.applicationplane.inspection.domain.ReactiveService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reactive services in the tenant's schema (search_path routing). */
public interface ReactiveServiceRepository extends JpaRepository<ReactiveService, UUID> {

    Optional<ReactiveService> findByIdAndOrgId(UUID id, UUID orgId);

    /** Token lookup is org-scoped too — the raiser is an authenticated org member. */
    Optional<ReactiveService> findByQrTokenAndOrgId(String qrToken, UUID orgId);

    List<ReactiveService> findAllByOrgIdOrderByCreatedAtDesc(UUID orgId);

    default List<ReactiveService> findAllByOrgId(UUID orgId) {
        return findAllByOrgIdOrderByCreatedAtDesc(orgId);
    }
}
