package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.ProcedureDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * The documents one procedure may cite: active, and either
     * organization-wide or in that procedure's own property.
     * {@code propertyId} is deliberately the <em>procedure's</em> property,
     * not the caller's current scope — an organization-wide procedure must
     * see only organization-wide documents even when edited from inside a
     * property, since citing a property's document would make it reachable
     * from everywhere through that procedure.
     *
     * <p>When {@code propertyId} is null, {@code d.propertyId = :propertyId}
     * compares against SQL NULL and is never true — ordinary three-valued
     * logic, not a special case — so the clause collapses to exactly
     * "organization-wide documents only".
     */
    @Query("select d from ProcedureDocument d where d.orgId = :orgId and d.active = true "
            + "and (d.propertyId is null or d.propertyId = :propertyId)")
    List<ProcedureDocument> findCitable(@Param("orgId") UUID orgId, @Param("propertyId") UUID propertyId);
}
