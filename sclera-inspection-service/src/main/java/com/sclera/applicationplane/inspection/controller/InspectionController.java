package com.sclera.applicationplane.inspection.controller;

import com.sclera.applicationplane.inspection.domain.InspectionStatus;
import com.sclera.applicationplane.inspection.dto.CreateInspectionRequest;
import com.sclera.applicationplane.inspection.dto.InspectionResponse;
import com.sclera.applicationplane.inspection.dto.SubmitAnswersRequest;
import com.sclera.applicationplane.inspection.service.InspectionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tenant-facing inspection execution API. Responses are wrapped in the standard
 * envelope by sclera-common's ResponseEnvelopeAdvice.
 */
@RestController
@RequestMapping("/api/v1/inspections")
public class InspectionController {

    private final InspectionService service;

    public InspectionController(InspectionService service) {

        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InspectionResponse create(@Valid @RequestBody CreateInspectionRequest request) {
        return service.create(request);
    }

    @GetMapping
    public Page<InspectionResponse> list(@RequestParam(required = false) InspectionStatus status,
                                         @RequestParam(required = false) UUID templateId,
                                         @PageableDefault(size = 20) Pageable pageable) {
        return service.list(status, templateId, pageable);
    }

    @GetMapping("/{id}")
    public InspectionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/start")
    public InspectionResponse start(@PathVariable UUID id) {
        return service.start(id);
    }

    @PutMapping("/{id}/answers")
    public InspectionResponse submitAnswers(@PathVariable UUID id,
                                            @Valid @RequestBody SubmitAnswersRequest request) {
        return service.submitAnswers(id, request);
    }

    @PostMapping("/{id}/complete")
    public InspectionResponse complete(@PathVariable UUID id) {
        return service.complete(id);
    }

    @PostMapping("/{id}/cancel")
    public InspectionResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
