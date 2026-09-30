package com.sclera.applicationplane.procedure.domain;

/**
 * Lifecycle of a procedure template's identity. Draft and published belong to
 * the {@link VersionState version}, not here.
 */
public enum TemplateStatus {
    ACTIVE,
    ARCHIVED
}
