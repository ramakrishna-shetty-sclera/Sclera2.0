package com.sclera.applicationplane.helper.storage;

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

/**
 * Where a reference document's bytes actually live. The procedure service
 * never implements this itself and never sees a file — it only ever holds the
 * opaque {@code location} this returns, which it stores and later hands back
 * to ask for a download URL.
 *
 * <p>Two implementations, chosen by {@code sclera.storage.provider}: a local
 * folder for dev ({@link LocalDocumentStore}, the default), and — not written
 * in this feature, only specified — an S3 bucket for production. Both must
 * return a <b>URL to follow, never bytes</b>: the real store signs a
 * short-lived GET against the bucket, the folder store returns a link back to
 * this service's own content endpoint. That symmetry is what lets a caller
 * treat both identically — follow the URL — without ever branching on which
 * one it is talking to.
 *
 * <p>{@code location} is a key, not a URL. A signed S3 URL expires in minutes;
 * storing one would be storing a dead link. What is stored is the key, and a
 * fresh URL is generated on every request to open the document.
 */
public interface DocumentStore {

    /**
     * Stores the bytes under a key scoped to the organization and returns the
     * location to keep. The filename is carried along for a human-readable key
     * in dev and for the content endpoint's {@code Content-Disposition}; it is
     * never trusted as a path on its own.
     */
    StoredDocument store(UUID orgId, String filename, InputStream content);

    /**
     * A URL the browser can follow to fetch the document. Never parsed by the
     * caller — what it points at is this store's own business.
     */
    URI downloadUrl(String location);

    /**
     * Whether this store actually holds something at {@code location}. What
     * the internal resolve endpoint answers before the procedure service
     * writes a library row, so a location that does not exist is refused at
     * creation rather than discovered the first time someone tries to open it.
     */
    boolean exists(String location);

    /**
     * True for every implementation that is not the real thing yet. Per C3,
     * a stub response is always marked — {@code stub: true} in the body,
     * {@code X-Sclera-Stub: true} on the response, the location itself
     * prefixed {@code stub-} — which is what stops a fake artifact reaching a
     * customer unnoticed and gives go-live exactly one thing to assert on.
     */
    boolean isStub();
}
