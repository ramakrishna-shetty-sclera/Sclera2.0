package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.ProcedureDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcedureDocumentRepository extends JpaRepository<ProcedureDocument, UUID> {

    /**
     * Every query is org-scoped — tenants never see each other's documents.
     * Row-level security narrows this further to the current property (or
     * organization-wide rows), so the result is already correctly scoped
     * without a second predicate here.
     */
    Optional<ProcedureDocument> findByIdAndOrgId(UUID id, UUID orgId);

    List<ProcedureDocument> findAllByOrgIdOrderByNameAsc(UUID orgId);

    List<ProcedureDocument> findAllByOrgIdAndActiveOrderByNameAsc(UUID orgId, boolean active);
}
