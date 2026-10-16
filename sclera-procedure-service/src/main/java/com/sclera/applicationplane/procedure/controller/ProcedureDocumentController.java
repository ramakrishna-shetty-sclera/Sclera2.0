package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.domain.ProcedureDocument;
import com.sclera.applicationplane.procedure.dto.ProcedureDocumentResponse;
import com.sclera.applicationplane.procedure.service.ProcedureDocumentService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * The library of reference documents a procedure may cite. Bytes are
 * uploaded straight here and stored on the row — no second service, no Dapr
 * call, the same way {@code AiController}'s document upload already works.
 * Responses are wrapped in the standard envelope by sclera-common's
 * ResponseEnvelopeAdvice, except {@link #content}, which is a raw byte
 * stream and deliberately bypasses it.
 *
 * <p>Guarded by the same org relations procedure authoring already uses: a
 * document is part of what a procedure may cite, so managing the library is
 * {@code can_manage_templates} and reading it is {@code can_view}. No new
 * OpenFGA relation exists for this, deliberately.
 */
@RestController
@RequestMapping("/api/v1/procedure-documents")
public class ProcedureDocumentController {

    private final ProcedureDocumentService service;

    public ProcedureDocumentController(ProcedureDocumentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public ProcedureDocumentResponse upload(@RequestParam("file") MultipartFile file) {
        return service.upload(file);
    }

    /** Omit {@code active} to get both active and inactive. */
    @GetMapping
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<ProcedureDocumentResponse> list(@RequestParam(required = false) Boolean active) {
        return service.list(active);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public ProcedureDocumentResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** The raw bytes, for opening or downloading the document — never wrapped in the response envelope. */
    @GetMapping("/{id}/content")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        ProcedureDocument document = service.getForContent(id);
        MediaType mediaType = document.getMimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(document.getMimeType());
        String filename = document.getName() == null ? "document" : document.getName();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(document.getContent());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public ProcedureDocumentResponse activate(@PathVariable UUID id) {
        return service.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public ProcedureDocumentResponse deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
