package com.sclera.applicationplane.calendar.repository;

import com.sclera.applicationplane.calendar.domain.CalendarSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarSettingsRepository extends JpaRepository<CalendarSettings, UUID> {

    List<CalendarSettings> findAllByOrgId(UUID orgId);

    Optional<CalendarSettings> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<CalendarSettings> findByOrgIdAndPropertyId(UUID orgId, UUID propertyId);

    Optional<CalendarSettings> findByOrgIdAndPropertyIdIsNull(UUID orgId);
}
