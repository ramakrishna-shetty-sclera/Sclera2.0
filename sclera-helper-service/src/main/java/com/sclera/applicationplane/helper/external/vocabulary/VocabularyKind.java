package com.sclera.applicationplane.helper.external.vocabulary;

/**
 * The four lists a procedure's target types are drawn from. The names are the
 * contract: the procedure service stores them as the {@code kind} of a target
 * type, so renaming one is a change to every published version that names it.
 */
public enum VocabularyKind {
    /** Where in the property tree: BUILDING, FLOOR, ... */
    HIERARCHY_LEVEL,
    /** What kind of place: ROOM, CORRIDOR, ... */
    LOCATION_TYPE,
    /** What kind of thing: EXTINGUISHER, ... Whatever the organization uses. */
    ASSET_CLASS,
    /** A free grouping an organization uses. */
    ASSET_TAG
}