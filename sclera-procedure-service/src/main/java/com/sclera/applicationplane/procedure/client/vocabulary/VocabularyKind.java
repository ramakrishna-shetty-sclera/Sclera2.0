package com.sclera.applicationplane.procedure.client.vocabulary;

/**
 * The four lists a target type is drawn from. The names are the contract with
 * whatever serves the vocabulary — today the helper service, later a property
 * service — and with the {@code kind} stored on every published version that
 * names a target type, so none of them is ever renamed.
 */
public enum VocabularyKind {
    HIERARCHY_LEVEL,
    LOCATION_TYPE,
    ASSET_CLASS,
    ASSET_TAG
}