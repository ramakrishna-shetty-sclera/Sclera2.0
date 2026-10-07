package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.ProcedureConsumer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** An organization's consumers, in its own schema (search_path routing). */
public interface ProcedureConsumerRepository extends JpaRepository<ProcedureConsumer, String> {

    /** The consumers a new procedure may name, in a stable order. */
    List<ProcedureConsumer> findAllByActiveOrderByKeyAsc(boolean active);
}