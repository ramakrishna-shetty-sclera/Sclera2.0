package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.GlobalProcedureTemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GlobalProcedureTemplateVersionRepository extends JpaRepository<GlobalProcedureTemplateVersion, UUID> {

    Optional<GlobalProcedureTemplateVersion> findByGlobalTemplateIdAndVersionNo(UUID globalTemplateId, int versionNo);

    List<GlobalProcedureTemplateVersion> findAllByGlobalTemplateIdOrderByVersionNoAsc(UUID globalTemplateId);
}
