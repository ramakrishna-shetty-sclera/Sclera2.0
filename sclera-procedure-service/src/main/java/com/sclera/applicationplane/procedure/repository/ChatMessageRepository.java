package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /** Full history in conversation order — what each LLM turn replays. */
    List<ChatMessage> findAllBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    long countBySessionId(UUID sessionId);
}
