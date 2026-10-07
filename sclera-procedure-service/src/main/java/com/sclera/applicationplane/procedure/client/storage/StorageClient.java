package com.sclera.applicationplane.procedure.client.storage;

/**
 * The port procedure-service codes against for reference-document storage.
 * Today the only implementation calls the helper service over Dapr, which
 * stands in for a real storage/document service per the project's helper-as-
 * scaffolding convention; this interface is what survives when that changes.
 */
public interface StorageClient {

    /** Does this location actually resolve to something the store holds? */
    boolean resolves(String location);
}
