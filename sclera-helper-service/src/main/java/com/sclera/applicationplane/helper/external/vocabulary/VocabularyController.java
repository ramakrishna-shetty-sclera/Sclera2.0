package com.sclera.applicationplane.helper.external.vocabulary;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The property vocabulary, read through the gateway as the signed-in user —
 * what a screen offers when an author picks a target type. Responses are
 * wrapped in the standard envelope by sclera-common.
 *
 * <p>Both calls return every entry with its {@code active} flag rather than
 * only the active ones: a screen offers the active ones for new use but still
 * has to name a retired one a procedure already carries.
 */
@RestController
@RequestMapping("/api/v1/helper/vocabulary")
public class VocabularyController {

    private final VocabularyService service;

    public VocabularyController(VocabularyService service) {
        this.service = service;
    }

    /** All four lists, keyed by kind. */
    @GetMapping
    @PreAuthorize("@fga.checkOrg('can_view')")
    public Map<VocabularyKind, List<VocabularyEntryResponse>> all() {
        return service.all(false);
    }

    /** One list. An unknown kind is a 400, not an empty list. */
    @GetMapping("/{kind}")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<VocabularyEntryResponse> one(@PathVariable VocabularyKind kind) {
        return service.list(kind, false);
    }
}