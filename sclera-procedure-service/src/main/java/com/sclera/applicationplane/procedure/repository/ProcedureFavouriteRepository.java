package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.ProcedureFavourite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcedureFavouriteRepository extends JpaRepository<ProcedureFavourite, ProcedureFavourite.Key> {

    Optional<ProcedureFavourite> findByUserIdAndTemplateId(UUID userId, UUID templateId);

    List<ProcedureFavourite> findAllByUserId(UUID userId);
}
