package com.sclera.applicationplane.procedure.domain;

/**
 * Whether a procedure belongs to the whole organization or to one property —
 * exactly what {@code procedure_template.property_id} being null or set
 * already means; this names the distinction for the list filter.
 *
 * <p>Named {@code TemplateScope} rather than bare {@code Scope} so it is
 * never confused with {@link com.sclera.applicationplane.procedure.definition
 * .DefinitionDocument.Scope}, the unrelated VERSION/SECTION threshold scope.
 */
public enum TemplateScope {
    ORGANIZATION,
    PROPERTY
}
