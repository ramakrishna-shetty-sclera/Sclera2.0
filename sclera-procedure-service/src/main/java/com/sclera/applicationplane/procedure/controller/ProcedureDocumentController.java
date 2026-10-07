package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.dto.ProcedureDocumentRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureDocumentResponse;
import com.sclera.applicationplane.procedure.service.ProcedureDocumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The library of reference documents a procedure may cite. Bytes never pass
 * through this service — upload goes straight to the helper service, which
 * hands back an opaque location; this call writes the metadata row. Responses
 * are wrapped in the standard envelope by sclera-common's
 * ResponseEnvelopeAdvice.
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
    public ProcedureDocumentResponse create(@Valid @RequestBody ProcedureDocumentRequest request) {
        return service.create(request);
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
}
