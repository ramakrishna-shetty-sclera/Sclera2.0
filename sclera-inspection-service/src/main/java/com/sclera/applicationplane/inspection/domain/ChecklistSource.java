package com.sclera.applicationplane.inspection.domain;

/**
 * Where a checklist came from (VDMS Task Dashboard groups by these).
 * Only INSPECTION is produced today; the rest arrive with the reactive-service
 * and tagged-procedure features.
 */
public enum ChecklistSource {
    INSPECTION,
    REACTIVE_SERVICE,
    TAGGED_PROCEDURE,
    SCHEDULED_SERVICE
}
