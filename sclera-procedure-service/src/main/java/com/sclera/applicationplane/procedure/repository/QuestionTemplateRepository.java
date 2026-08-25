package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.QuestionTemplate;
import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface QuestionTemplateRepository extends JpaRepository<QuestionTemplate, UUID> {

    /** Every query is org-scoped — tenants never see each other's templates. */
    Optional<QuestionTemplate> findByIdAndOrgId(UUID id, UUID orgId);

    Page<QuestionTemplate> findAllByOrgId(UUID orgId, Pageable pageable);

    Page<QuestionTemplate> findAllByOrgIdAndStatus(UUID orgId, TemplateStatus status, Pageable pageable);

    boolean existsByOrgIdAndNameIgnoreCaseAndStatusNot(UUID orgId, String name, TemplateStatus status);
}
