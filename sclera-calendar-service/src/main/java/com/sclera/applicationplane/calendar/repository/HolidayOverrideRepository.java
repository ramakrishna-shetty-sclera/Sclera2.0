package com.sclera.applicationplane.calendar.repository;

import com.sclera.applicationplane.calendar.domain.HolidayOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HolidayOverrideRepository extends JpaRepository<HolidayOverride, UUID> {

    List<HolidayOverride> findAllByOrgId(UUID orgId);

    List<HolidayOverride> findAllByOrgIdAndPropertyId(UUID orgId, UUID propertyId);

    List<HolidayOverride> findAllByHolidayId(UUID holidayId);

    Optional<HolidayOverride> findByIdAndOrgId(UUID id, UUID orgId);
}
