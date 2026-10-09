package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** No org scoping here — the whole point of this table is that it has none. */
public interface GlobalProcedureTemplateRepository extends JpaRepository<GlobalProcedureTemplate, UUID> {

    /**
     * Row lock for anything that mints keys or version numbers, so two
     * concurrent saves can never hand out the same one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from GlobalProcedureTemplate t where t.id = :id")
    Optional<GlobalProcedureTemplate> lockById(UUID id);

    /**
     * The library as a reader sees it: active templates that have a published
     * version, since one without cannot be imported.
     *
     * <p>Both filters are always present, as a non-null value that means "no
     * filter" ({@code ''} for the consumer, {@code '%'} for the pattern), rather
     * than a nullable parameter. A null parameter in an expression such as
     * {@code lower(:x)} leaves the database unable to work out its type, and this
     * service has already been bitten once by a nullable parameter in a query.
     *
     * <p>{@code pattern} is a lower-cased LIKE pattern whose wildcards the caller
     * has escaped with {@code !}.
     */
    @Query("""
            select t from GlobalProcedureTemplate t
            where t.status = com.sclera.applicationplane.procedure.domain.TemplateStatus.ACTIVE
              and t.currentPublishedVersionId is not null
              and (:consumer = '' or t.consumerKey = :consumer)
              and (lower(t.name) like :pattern escape '!'
                   or lower(coalesce(t.description, '')) like :pattern escape '!')
            """)
    Page<GlobalProcedureTemplate> browse(@Param("consumer") String consumer,
                                         @Param("pattern") String pattern,
                                         Pageable pageable);
}
