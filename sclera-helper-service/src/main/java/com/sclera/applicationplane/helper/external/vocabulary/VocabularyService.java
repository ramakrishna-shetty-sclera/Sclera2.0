package com.sclera.applicationplane.helper.external.vocabulary;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The property vocabulary: the real rows of the organization in the current
 * tenant schema, not a stand-in. This is the part of the helper that is
 * meant to be correct — the procedure service validates target-type keys
 * against it, and a vocabulary that lied would let a procedure be published
 * against a key that does not exist.
 *
 * <p>Read-only. Callers must already be inside the organization's schema:
 * the public controller through the request's JWT, the internal one through
 * {@code TenantContext.runAs}.
 */
@Service
@Transactional(readOnly = true)
public class VocabularyService {

    private final Map<VocabularyKind, VocabularyRepository<? extends VocabularyEntry>> repositories =
            new EnumMap<>(VocabularyKind.class);

    public VocabularyService(HierarchyLevelRepository hierarchyLevels,
                             LocationTypeEntryRepository locationTypes,
                             AssetClassRepository assetClasses,
                             AssetTagRepository assetTags) {
        repositories.put(VocabularyKind.HIERARCHY_LEVEL, hierarchyLevels);
        repositories.put(VocabularyKind.LOCATION_TYPE, locationTypes);
        repositories.put(VocabularyKind.ASSET_CLASS, assetClasses);
        repositories.put(VocabularyKind.ASSET_TAG, assetTags);
    }

    /**
     * One list, in display order. {@code activeOnly} leaves out entries that
     * have been retired: what a new procedure may name. A published version
     * that already names a retired key is validated against the full list.
     */
    public List<VocabularyEntryResponse> list(VocabularyKind kind, boolean activeOnly) {
        VocabularyRepository<? extends VocabularyEntry> repository = repositories.get(kind);
        List<? extends VocabularyEntry> rows = activeOnly
                ? repository.findAllByActiveOrderByDisplayOrderAscKeyAsc(true)
                : repository.findAllByOrderByDisplayOrderAscKeyAsc();
        return rows.stream().map(VocabularyEntryResponse::of).toList();
    }

    /** All four lists in one call, every kind present even when empty. */
    public Map<VocabularyKind, List<VocabularyEntryResponse>> all(boolean activeOnly) {
        Map<VocabularyKind, List<VocabularyEntryResponse>> out = new EnumMap<>(VocabularyKind.class);
        for (VocabularyKind kind : VocabularyKind.values()) {
            out.put(kind, list(kind, activeOnly));
        }
        return out;
    }
}