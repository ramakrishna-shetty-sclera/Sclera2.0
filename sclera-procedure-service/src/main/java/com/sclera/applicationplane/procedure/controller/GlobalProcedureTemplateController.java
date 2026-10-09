package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.CreateGlobalTemplateRequest;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.GlobalPublishResponse;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.GlobalTemplateResponse;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.GlobalVersionResponse;
import com.sclera.applicationplane.procedure.dto.GlobalProcedureTemplateDtos.SaveGlobalDraftRequest;
import com.sclera.applicationplane.procedure.service.GlobalProcedureTemplateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Authoring Sclera's own shared library — platform-admin only, since this
 * content belongs to no organization at all. Guarded with
 * {@code @fga.isPlatformAdmin()} rather than an object or org relation,
 * for exactly that reason.
 */
@RestController
@RequestMapping("/api/v1/global-procedure-templates")
public class GlobalProcedureTemplateController {

    private final GlobalProcedureTemplateService service;

    public GlobalProcedureTemplateController(GlobalProcedureTemplateService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.isPlatformAdmin()")
    public GlobalTemplateResponse create(@Valid @RequestBody CreateGlobalTemplateRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}/draft")
    @PreAuthorize("@fga.isPlatformAdmin()")
    public GlobalVersionResponse getDraft(@PathVariable UUID id) {
        return service.getDraft(id);
    }

    /** Starts a new draft from the current published version — the only way to produce v2 and beyond. */
    @PostMapping("/{id}/draft")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.isPlatformAdmin()")
    public GlobalVersionResponse createDraft(@PathVariable UUID id) {
        return service.createDraft(id);
    }

    @PutMapping("/{id}/draft")
    @PreAuthorize("@fga.isPlatformAdmin()")
    public GlobalVersionResponse saveDraft(@PathVariable UUID id, @Valid @RequestBody SaveGlobalDraftRequest request) {
        return service.saveDraft(id, request);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("@fga.isPlatformAdmin()")
    public GlobalPublishResponse publish(@PathVariable UUID id) {
        return service.publish(id);
    }
}
