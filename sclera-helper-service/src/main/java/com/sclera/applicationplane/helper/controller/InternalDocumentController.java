package com.sclera.applicationplane.helper.controller;

import com.sclera.applicationplane.helper.dto.ResolveLocationRequest;
import com.sclera.applicationplane.helper.dto.ResolveLocationResponse;
import com.sclera.applicationplane.helper.storage.DocumentStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by the procedure service over Dapr invocation, before it writes a
 * library row: does this location actually resolve to something this store
 * holds? Storage has no tenant dimension — it never touches Postgres or a
 * schema — so, unlike every other internal endpoint in this codebase, there is
 * no organization to pin and no {@code TenantContext.runAs}.
 *
 * <p>No {@code @PreAuthorize}, because there is no JWT. Guarded by HMAC
 * signing plus {@code InternalEndpointFilter} and permitted in
 * {@code SecurityConfig} on that basis, the same as every {@code /internal/**}
 * endpoint in the other two services.
 */
@RestController
@RequestMapping("/internal/api/v1/documents")
public class InternalDocumentController {

    private final DocumentStore store;

    public InternalDocumentController(DocumentStore store) {
        this.store = store;
    }

    @PostMapping("/resolve")
    public ResolveLocationResponse resolve(@RequestBody ResolveLocationRequest request) {
        return new ResolveLocationResponse(store.exists(request.location()));
    }
}
