package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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
}
