package com.sclera.applicationplane.procedure.dto;

import com.sclera.applicationplane.procedure.domain.LinkState;

/** Whether a linked copy is behind the global template it was imported from. */
public final class UpdateDtos {

    private UpdateDtos() {
    }

    /**
     * {@code linkState}/{@code appliedVersionNo} are null when the template
     * was never imported from the shared library at all — a normal state,
     * not an error, and distinct from {@code LinkState.STANDALONE} (imported,
     * then forked, deliberately no longer tracking).
     *
     * <p>{@code updateAvailable} is derived fresh on every read, never
     * stored: {@code appliedVersionNo < currentVersionNo}, true whether the
     * link is {@code LINKED} or {@code DEFERRED} — deferring records that an
     * update was seen and passed over once, it does not hide that one is
     * still there to apply. Only {@code STANDALONE} forces it false: a
     * forked copy has stopped tracking the source at all, by an edit or an
     * explicit unlink, and comparing it against a source it no longer
     * follows would be misleading.
     */
    public record UpdateStatus(
            LinkState linkState,
            Integer appliedVersionNo,
            Integer currentVersionNo,
            boolean updateAvailable) {}
}
