package com.sclera.applicationplane.inspection.domain;

/**
 * Checklist lifecycle (VDMS): TODO → COMPLETE, plus the off-happy-path states.
 * FAILED  — a question was answered failed on submit.
 * EXCEPTION — moved aside with a reason during filling.
 * INCOMPLETE — not filled by the due date (can be reopened to TODO).
 */
public enum ChecklistStatus {
    TODO,
    COMPLETE,
    FAILED,
    EXCEPTION,
    INCOMPLETE
}
