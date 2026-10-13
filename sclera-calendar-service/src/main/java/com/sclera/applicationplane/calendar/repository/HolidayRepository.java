package com.sclera.applicationplane.calendar.repository;

import com.sclera.applicationplane.calendar.domain.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    List<Holiday> findAllByOrgId(UUID orgId);

    List<Holiday> findAllByOrgIdAndEnabledTrue(UUID orgId);

    Optional<Holiday> findByIdAndOrgId(UUID id, UUID orgId);

    List<Holiday> findAllByOrgIdAndSource(UUID orgId, String source);
}
