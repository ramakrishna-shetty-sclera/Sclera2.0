package com.sclera.applicationplane.inspection.domain;

/** Inspection priority. Null on a config means "not selected" (the form's "Select" placeholder). */
public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
