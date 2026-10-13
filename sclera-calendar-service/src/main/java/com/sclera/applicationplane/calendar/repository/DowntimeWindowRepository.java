package com.sclera.applicationplane.calendar.repository;

import com.sclera.applicationplane.calendar.domain.DowntimeWindow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DowntimeWindowRepository extends JpaRepository<DowntimeWindow, UUID> {

    List<DowntimeWindow> findAllByOrgId(UUID orgId);

    List<DowntimeWindow> findAllByOrgIdAndActiveTrue(UUID orgId);

    Optional<DowntimeWindow> findByIdAndOrgId(UUID id, UUID orgId);
}
