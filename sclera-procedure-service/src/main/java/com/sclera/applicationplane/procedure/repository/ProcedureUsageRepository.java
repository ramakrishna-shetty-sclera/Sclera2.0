package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.ProcedureUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What consumers last reported. Lives in each organization's schema, so a report
 * is only ever compared with its own organization's.
 */
public interface ProcedureUsageRepository extends JpaRepository<ProcedureUsage, UUID> {

    /** One row per version a template has consumers on: its number and how many. */
    interface VersionCount {
        int getVersionNo();

        long getConsumerCount();
    }

    /**
     * How many consumers are on each version of a template, oldest version first.
     * The version numbers come from the same join, so there is no query per row.
     * Neither table has row-level security, which is why a property's template is
     * counted from organization level too.
     */
    @Query("""
            SELECT v.versionNo AS versionNo, COUNT(u) AS consumerCount
            FROM ProcedureUsage u, ProcedureTemplateVersion v
            WHERE u.versionId = v.id AND u.templateId = :templateId
            GROUP BY v.versionNo
            ORDER BY v.versionNo
            """)
    List<VersionCount> countByVersion(@Param("templateId") UUID templateId);

    /** The one row a consumer's report about a template can be, if it has reported before. */
    Optional<ProcedureUsage> findByConsumerKeyAndConsumerRefIdAndTemplateId(
            String consumerKey, String consumerRefId, UUID templateId);
}
