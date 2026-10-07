package com.sclera.applicationplane.helper.controller;

import com.sclera.applicationplane.helper.storage.LocalDocumentStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves bytes straight out of the local folder. Only exists while the local
 * provider is active — a real object store's download URL points at the
 * bucket directly, so nothing ever calls back into helper to fetch it, and in
 * production this route simply is not registered.
 *
 * <p>Not wrapped in the standard envelope: this returns a file, not JSON.
 */
@RestController
@RequestMapping("/api/v1/helper/documents")
@ConditionalOnProperty(name = "sclera.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalDocumentContentController {

    private final LocalDocumentStore store;

    public LocalDocumentContentController(LocalDocumentStore store) {
        this.store = store;
    }

    @GetMapping("/content")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public ResponseEntity<Resource> content(@RequestParam String location) {
        Resource resource = store.load(location);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Sclera-Stub", "true")
                .body(resource);
    }
}
