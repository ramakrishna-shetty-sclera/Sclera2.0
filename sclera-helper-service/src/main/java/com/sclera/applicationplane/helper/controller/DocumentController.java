package com.sclera.applicationplane.helper.controller;

import com.sclera.applicationplane.helper.dto.DocumentUploadResponse;
import com.sclera.applicationplane.helper.dto.DocumentUrlResponse;
import com.sclera.applicationplane.helper.storage.DocumentStore;
import com.sclera.applicationplane.helper.storage.StoredDocument;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import com.sclera.controlplane.common.security.OrgContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Stores a reference document's bytes and hands back an opaque location —
 * bytes go through this service directly from the browser, never through the
 * procedure service. Responses are wrapped in the standard envelope by
 * sclera-common, except where a stub marker header is added alongside it.
 *
 * <p>Gated by {@code can_manage_assets} / {@code can_view}, the same split
 * {@code LocationController} and {@code AssetController} already use — helper
 * has no relation of its own for documents, and this is the closest existing
 * one: a privileged org member may add to shared organizational content, any
 * member may read it.
 */
@RestController
@RequestMapping("/api/v1/helper/documents")
public class DocumentController {

    private static final String STUB_HEADER = "X-Sclera-Stub";

    private final DocumentStore store;

    public DocumentController(DocumentStore store) {
        this.store = store;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@fga.checkOrg('can_manage_assets')")
    public ResponseEntity<DocumentUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessRuleException("The uploaded file is empty");
        }
        StoredDocument stored;
        try {
            stored = store.store(OrgContext.getOrgId(), file.getOriginalFilename(), file.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
        DocumentUploadResponse body = new DocumentUploadResponse(
                stored.location(), store.downloadUrl(stored.location()).toString(),
                stored.sizeBytes(), store.isStub());
        return withStubHeader(ResponseEntity.status(HttpStatus.CREATED), body);
    }

    /** A fresh link for a location already on file — generated now, never cached. */
    @GetMapping("/url")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public ResponseEntity<DocumentUrlResponse> url(@RequestParam String location) {
        if (!store.exists(location)) {
            throw new BusinessRuleException("No document at that location");
        }
        return withStubHeader(ResponseEntity.ok(),
                new DocumentUrlResponse(store.downloadUrl(location).toString(), store.isStub()));
    }

    private <T> ResponseEntity<T> withStubHeader(ResponseEntity.BodyBuilder builder, T body) {
        if (store.isStub()) {
            builder.header(STUB_HEADER, "true");
        }
        return builder.body(body);
    }
}
