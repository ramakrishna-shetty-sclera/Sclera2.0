package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    Optional<ChatSession> findByIdAndOrgId(UUID id, UUID orgId);
}
