package com.sclera.applicationplane.procedure.domain;

/**
 * Whether an organization's copy of a global template is still tracking the
 * global template's updates. "Update available" is never stored — it is
 * always derived by comparing {@code applied_version_no} against the global
 * template's current published version, so it can never go stale.
 */
public enum LinkState {
    /** Still tracking the global template's updates. */
    LINKED,
    /** An update exists and was explicitly passed over. Feature 11 writes this. */
    DEFERRED,
    /** Forked — by editing the copy, or by an explicit unlink — and no longer tracks updates. */
    STANDALONE
}
