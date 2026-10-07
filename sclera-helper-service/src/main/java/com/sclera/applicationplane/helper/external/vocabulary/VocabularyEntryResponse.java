package com.sclera.applicationplane.helper.external.vocabulary;

/** One vocabulary entry as the wire sees it. */
public record VocabularyEntryResponse(String key, String name, int displayOrder, boolean active) {

    static VocabularyEntryResponse of(VocabularyEntry entry) {
        return new VocabularyEntryResponse(entry.getKey(), entry.getName(), entry.getDisplayOrder(), entry.isActive());
    }
}