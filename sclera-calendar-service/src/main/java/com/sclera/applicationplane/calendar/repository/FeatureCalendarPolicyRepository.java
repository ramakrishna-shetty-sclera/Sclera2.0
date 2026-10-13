package com.sclera.applicationplane.calendar.repository;

import com.sclera.applicationplane.calendar.domain.FeatureCalendarPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeatureCalendarPolicyRepository extends JpaRepository<FeatureCalendarPolicy, UUID> {

    List<FeatureCalendarPolicy> findAllByOrgId(UUID orgId);

    Optional<FeatureCalendarPolicy> findByOrgIdAndFeatureCodeAndPropertyId(
            UUID orgId, String featureCode, UUID propertyId);

    Optional<FeatureCalendarPolicy> findByOrgIdAndFeatureCodeAndPropertyIdIsNull(
            UUID orgId, String featureCode);
}
