package com.sclera.applicationplane.helper.storage;

/** What a store hands back right after writing the bytes. */
public record StoredDocument(String location, long sizeBytes) {
}
