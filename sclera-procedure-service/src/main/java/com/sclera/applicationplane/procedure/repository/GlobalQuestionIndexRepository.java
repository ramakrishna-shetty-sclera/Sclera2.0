package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.GlobalQuestionIndexEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * The question bank's rows. Lives in {@code public}, so there is no tenant to
 * scope a query to: every organization reads the same library.
 */
public interface GlobalQuestionIndexRepository extends JpaRepository<GlobalQuestionIndexEntry, UUID> {

    List<GlobalQuestionIndexEntry> findAllByGlobalVersionIdOrderByQuestionKey(UUID globalVersionId);

    /** Clears one version's rows, so indexing it again replaces them rather than doubling them. */
    @Modifying
    @Query("delete from GlobalQuestionIndexEntry e where e.globalVersionId = :versionId")
    int deleteByGlobalVersionId(@Param("versionId") UUID versionId);
}
