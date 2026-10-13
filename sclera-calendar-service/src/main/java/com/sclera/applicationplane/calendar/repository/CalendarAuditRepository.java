package com.sclera.applicationplane.calendar.repository;

import com.sclera.applicationplane.calendar.domain.CalendarAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CalendarAuditRepository extends JpaRepository<CalendarAudit, UUID> {

    List<CalendarAudit> findAllByEntityTypeAndEntityIdOrderByAtDesc(String entityType, UUID entityId);
}
