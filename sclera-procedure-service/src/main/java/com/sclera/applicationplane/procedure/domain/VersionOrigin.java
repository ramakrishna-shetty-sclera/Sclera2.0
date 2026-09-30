package com.sclera.applicationplane.procedure.domain;

/** How a version came to exist. Only AUTHORED is produced so far. */
public enum VersionOrigin {
    AUTHORED,
    MIGRATED,
    IMPORTED,
    GLOBAL_PUSH
}
