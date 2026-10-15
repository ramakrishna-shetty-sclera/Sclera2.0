package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.GeneratedQuestion;
import com.sclera.applicationplane.procedure.domain.GeneratedQuestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GeneratedQuestionRepository extends JpaRepository<GeneratedQuestion, UUID> {

    List<GeneratedQuestion> findAllByTemplateIdAndOrgIdOrderByCreatedAtAsc(UUID templateId, UUID orgId);

    List<GeneratedQuestion> findAllByTemplateIdAndOrgIdAndStatusOrderByCreatedAtAsc(
            UUID templateId, UUID orgId, GeneratedQuestionStatus status);

    List<GeneratedQuestion> findAllBySourceRefOrderByCreatedAtAsc(UUID sourceRef);

    List<GeneratedQuestion> findAllBySourceRefAndStatusOrderByCreatedAtAsc(
            UUID sourceRef, GeneratedQuestionStatus status);

    List<GeneratedQuestion> findAllByIdInAndOrgId(Collection<UUID> ids, UUID orgId);
}
