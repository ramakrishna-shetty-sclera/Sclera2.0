package com.sclera.applicationplane.procedure.domain;

/** Lifecycle of an uploaded source document's text extraction. */
public enum AiDocumentStatus {
    PENDING,
    EXTRACTING,
    READY,
    FAILED
}
