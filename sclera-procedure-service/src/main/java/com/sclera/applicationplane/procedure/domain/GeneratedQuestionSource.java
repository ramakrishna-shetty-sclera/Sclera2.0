package com.sclera.applicationplane.procedure.domain;

/**
 * Which feature produced a candidate. Maps onto {@link ItemSource} at import:
 * DOCUMENT → {@code ItemSource.DOCUMENT}, CHAT → {@code ItemSource.SUGGESTED}.
 */
public enum GeneratedQuestionSource {
    DOCUMENT,
    CHAT
}
