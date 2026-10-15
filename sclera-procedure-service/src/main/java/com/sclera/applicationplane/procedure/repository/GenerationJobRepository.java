package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.GenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {

    Optional<GenerationJob> findByIdAndOrgId(UUID id, UUID orgId);
}
