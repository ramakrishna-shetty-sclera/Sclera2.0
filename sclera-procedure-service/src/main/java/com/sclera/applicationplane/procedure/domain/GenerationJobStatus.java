package com.sclera.applicationplane.procedure.domain;

/** Lifecycle of a document-driven question generation job. */
public enum GenerationJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}
