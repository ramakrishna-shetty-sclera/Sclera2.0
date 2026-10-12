package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.AiDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiDocumentRepository extends JpaRepository<AiDocument, UUID> {

    Optional<AiDocument> findByIdAndOrgId(UUID id, UUID orgId);

    List<AiDocument> findAllByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
