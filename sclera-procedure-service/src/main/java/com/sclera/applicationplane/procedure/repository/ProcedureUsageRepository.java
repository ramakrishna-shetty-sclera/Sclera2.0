package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.ProcedureUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * What consumers last reported. Lives in each organization's schema, so a report
 * is only ever compared with its own organization's. The query that reads it for
 * a template's author comes with the endpoint that needs it.
 */
public interface ProcedureUsageRepository extends JpaRepository<ProcedureUsage, UUID> {

    /** The one row a consumer's report about a template can be, if it has reported before. */
    Optional<ProcedureUsage> findByConsumerKeyAndConsumerRefIdAndTemplateId(
            String consumerKey, String consumerRefId, UUID templateId);
}
