package com.sclera.applicationplane.procedure.domain;

/**
 * Review state of a generated candidate question. Only an explicit author
 * action moves DRAFT anywhere: import puts it (keyed) into the template's
 * draft definition, discard drops it from review.
 */
public enum GeneratedQuestionStatus {
    DRAFT,
    IMPORTED,
    DISCARDED
}
