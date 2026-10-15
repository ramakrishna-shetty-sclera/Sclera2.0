package com.sclera.applicationplane.procedure.domain;

/**
 * DRAFT is the one mutable version a template may have. PUBLISHED is frozen
 * forever. ARCHIVED is a published version withdrawn from use; still readable,
 * because checklists that pinned it must keep rendering.
 */
public enum VersionState {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
