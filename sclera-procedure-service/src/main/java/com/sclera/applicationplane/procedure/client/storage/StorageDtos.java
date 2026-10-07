package com.sclera.applicationplane.procedure.client.storage;

/**
 * Hand-copied against helper's own {@code ResolveLocationRequest} /
 * {@code ResolveLocationResponse} — no compile-time dependency between the two
 * services, same as every other Dapr client in this codebase.
 */
public final class StorageDtos {

    private StorageDtos() {}

    public record ResolveLocationRequest(String location) {}

    public record ResolveLocationResponse(boolean exists) {}
}
