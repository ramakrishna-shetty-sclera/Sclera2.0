package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CloneRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.CreateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.DiffResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.NewDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.PublishRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.PublishResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.SaveDraftRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.TemplateResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.UpdateTemplateRequest;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionResponse;
import com.sclera.applicationplane.procedure.dto.ProcedureTemplateDtos.VersionSummary;
import com.sclera.applicationplane.procedure.service.ProcedureTemplateService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Procedure templates and their versions. Responses are wrapped in the
 * standard envelope by sclera-common's ResponseEnvelopeAdvice.
 */
@RestController
@RequestMapping("/api/v1/procedure-templates")
public class ProcedureTemplateController {

    private final ProcedureTemplateService service;

    public ProcedureTemplateController(ProcedureTemplateService service) {
        this.service = service;
    }

    // --- template identity --------------------------------------------------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public TemplateResponse create(@Valid @RequestBody CreateTemplateRequest request) {
        return service.create(request);
    }

    @GetMapping
    @PreAuthorize("@fga.checkOrg('can_view')")
    public Page<TemplateResponse> list(@RequestParam(required = false) TemplateStatus status,
                                       @PageableDefault(size = 20) Pageable pageable) {
        return service.list(status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_view')")
    public TemplateResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_edit')")
    public TemplateResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTemplateRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_delete')")
    public TemplateResponse archive(@PathVariable UUID id) {
        return service.archive(id);
    }

    @PostMapping("/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_view') and @fga.checkOrg('can_manage_templates')")
    public TemplateResponse cloneTemplate(@PathVariable UUID id, @Valid @RequestBody CloneRequest request) {
        return service.cloneTemplate(id, request);
    }

    // --- the draft ----------------------------------------------------------

    @GetMapping("/{id}/draft")
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_view')")
    public VersionResponse getDraft(@PathVariable UUID id) {
        return service.getDraft(id);
    }

    @PutMapping("/{id}/draft")
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_edit')")
    public VersionResponse saveDraft(@PathVariable UUID id, @Valid @RequestBody SaveDraftRequest request) {
        return service.saveDraft(id, request);
    }

    @PostMapping("/{id}/draft")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_edit')")
    public VersionResponse createDraft(@PathVariable UUID id, @Valid @RequestBody NewDraftRequest request) {
        return service.createDraft(id, request);
    }

    @DeleteMapping("/{id}/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_edit')")
    public void discardDraft(@PathVariable UUID id) {
        service.discardDraft(id);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_publish')")
    public PublishResponse publish(@PathVariable UUID id, @RequestBody(required = false) PublishRequest request) {
        return service.publish(id, request);
    }

    // --- history ------------------------------------------------------------

    @GetMapping("/{id}/versions")
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_view')")
    public List<VersionSummary> listVersions(@PathVariable UUID id) {
        return service.listVersions(id);
    }

    @GetMapping("/{id}/versions/{versionNo}")
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_view')")
    public VersionResponse getVersion(@PathVariable UUID id, @PathVariable int versionNo) {
        return service.getVersion(id, versionNo);
    }

    @GetMapping("/{id}/diff")
    @PreAuthorize("@fga.check('procedure_template', #id, 'can_view')")
    public DiffResponse diff(@PathVariable UUID id, @RequestParam int from, @RequestParam int to) {
        return service.diff(id, from, to);
    }
}
