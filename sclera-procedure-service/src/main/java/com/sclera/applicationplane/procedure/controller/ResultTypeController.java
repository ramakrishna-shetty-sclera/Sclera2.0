package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.dto.ResultTypeReorderRequest;
import com.sclera.applicationplane.procedure.dto.ResultTypeRequest;
import com.sclera.applicationplane.procedure.dto.ResultTypeResponse;
import com.sclera.applicationplane.procedure.dto.ResultTypeUpdateRequest;
import com.sclera.applicationplane.procedure.service.ResultTypeService;
import jakarta.validation.Valid;
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
 * The organization's result types — the outcomes an answer, question, category
 * or whole inspection can produce. Responses are wrapped in the standard
 * envelope by sclera-common's ResponseEnvelopeAdvice.
 *
 * <p>Result types are org-level settings, so they are guarded by org relations
 * rather than a per-object OpenFGA type. {@code can_manage_templates} is an
 * interim: a dedicated {@code can_manage_result_types} relation is split out
 * when the authorization model is extended.
 */
@RestController
@RequestMapping("/api/v1/result-types")
public class ResultTypeController {

    private final ResultTypeService service;

    public ResultTypeController(ResultTypeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public ResultTypeResponse create(@Valid @RequestBody ResultTypeRequest request) {
        return service.create(request);
    }

    /** Ordered most severe first. Omit {@code active} to get both active and inactive. */
    @GetMapping
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<ResultTypeResponse> list(@RequestParam(required = false) Boolean active) {
        return service.list(active);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public ResultTypeResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public ResultTypeResponse update(@PathVariable UUID id,
                                     @Valid @RequestBody ResultTypeUpdateRequest request) {
        return service.update(id, request);
    }

    /** Drag-to-reorder. Takes every result type in the organization, most severe first. */
    @PostMapping("/reorder")
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public List<ResultTypeResponse> reorder(@Valid @RequestBody ResultTypeReorderRequest request) {
        return service.reorder(request.orderedIds());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public ResultTypeResponse activate(@PathVariable UUID id) {
        return service.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public ResultTypeResponse deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@fga.checkOrg('can_manage_templates')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
