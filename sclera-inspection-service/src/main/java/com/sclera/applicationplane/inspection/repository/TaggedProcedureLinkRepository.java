package com.sclera.applicationplane.inspection.repository;

import com.sclera.applicationplane.inspection.domain.TaggedProcedureLink;
import com.sclera.applicationplane.inspection.domain.TargetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tagged-procedure template links in the tenant's schema. */
public interface TaggedProcedureLinkRepository extends JpaRepository<TaggedProcedureLink, UUID> {

    Optional<TaggedProcedureLink> findByIdAndOrgId(UUID id, UUID orgId);

    List<TaggedProcedureLink> findAllByOrgIdOrderByCreatedAtDesc(UUID orgId);

    boolean existsByOrgIdAndProcedureIdAndTargetTypeAndTargetId(
            UUID orgId, UUID procedureId, TargetType targetType, UUID targetId);

    default List<TaggedProcedureLink> findAllByOrgId(UUID orgId, TargetType targetType, UUID targetId) {
        return findAllByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .filter(l -> targetType == null || l.getTargetType() == targetType)
                .filter(l -> targetId == null || targetId.equals(l.getTargetId()))
                .toList();
    }

    default boolean exists(UUID orgId, UUID procedureId, TargetType targetType, UUID targetId) {
        return existsByOrgIdAndProcedureIdAndTargetTypeAndTargetId(orgId, procedureId, targetType, targetId);
    }
}
