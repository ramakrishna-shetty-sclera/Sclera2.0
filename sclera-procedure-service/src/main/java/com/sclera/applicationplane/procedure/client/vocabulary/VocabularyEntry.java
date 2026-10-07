package com.sclera.applicationplane.procedure.client.vocabulary;

/**
 * One key in one list. Owned by this service rather than borrowed from the
 * helper's classes, so that swapping the helper for a real property service
 * changes configuration and not this package.
 *
 * @param active false once the key has been retired: it may not be named by
 *               anything new, but a version that already names it stays valid
 */
public record VocabularyEntry(String key, String name, int displayOrder, boolean active) {
}