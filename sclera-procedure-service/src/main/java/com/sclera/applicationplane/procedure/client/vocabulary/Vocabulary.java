package com.sclera.applicationplane.procedure.client.vocabulary;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One organization's whole vocabulary: all four lists, as they were when it
 * was read. Immutable, so a cached copy can be handed to any number of callers.
 */
public final class Vocabulary {

    private final Map<VocabularyKind, List<VocabularyEntry>> entries = new EnumMap<>(VocabularyKind.class);
    private final Map<VocabularyKind, Set<String>> allKeys = new EnumMap<>(VocabularyKind.class);
    private final Map<VocabularyKind, Set<String>> activeKeys = new EnumMap<>(VocabularyKind.class);

    /** A kind missing from {@code lists} is an empty list, never an error. */
    public Vocabulary(Map<VocabularyKind, List<VocabularyEntry>> lists) {
        for (VocabularyKind kind : VocabularyKind.values()) {
            List<VocabularyEntry> list = lists == null || lists.get(kind) == null ? List.of() : List.copyOf(lists.get(kind));
            entries.put(kind, list);
            allKeys.put(kind, list.stream().map(VocabularyEntry::key).collect(Collectors.toUnmodifiableSet()));
            activeKeys.put(kind, list.stream().filter(VocabularyEntry::active)
                    .map(VocabularyEntry::key).collect(Collectors.toUnmodifiableSet()));
        }
    }

    public List<VocabularyEntry> entries(VocabularyKind kind) {
        return entries.get(kind);
    }

    /**
     * Whether the organization has this key at all, retired or not. This is
     * the test for a version that already names it: retiring a key must not
     * make a published procedure invalid after the fact.
     */
    public boolean contains(VocabularyKind kind, String key) {
        return allKeys.get(kind).contains(key);
    }

    /** Whether the key may be named by something new — present and not retired. */
    public boolean isActive(VocabularyKind kind, String key) {
        return activeKeys.get(kind).contains(key);
    }
}