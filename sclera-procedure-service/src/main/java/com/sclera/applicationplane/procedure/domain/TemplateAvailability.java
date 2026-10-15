package com.sclera.applicationplane.procedure.domain;

/**
 * Where a procedure may be used: the property it was authored in, or every
 * property in the organization. Advisory metadata for now — visibility is
 * enforced by property scoping, not by this flag.
 */
public enum TemplateAvailability {
    PROPERTY,
    ORG
}
