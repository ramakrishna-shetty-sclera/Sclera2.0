package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** No org scoping here — the whole point of this table is that it has none. */
public interface GlobalProcedureTemplateRepository extends JpaRepository<GlobalProcedureTemplate, UUID> {
}
