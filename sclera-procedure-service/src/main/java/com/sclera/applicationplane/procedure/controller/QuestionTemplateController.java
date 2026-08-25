package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.domain.TemplateStatus;
import com.sclera.applicationplane.procedure.dto.QuestionTemplateRequest;
import com.sclera.applicationplane.procedure.dto.QuestionTemplateResponse;
import com.sclera.applicationplane.procedure.service.QuestionTemplateService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
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

import java.util.UUID;

/**
 * Tenant-facing template authoring API. Responses are wrapped in the standard
 * envelope by sclera-common's ResponseEnvelopeAdvice.
 */
@RestController
@RequestMapping("/api/v1/question-templates")
public class QuestionTemplateController {

    private final QuestionTemplateService service;

    public QuestionTemplateController(QuestionTemplateService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionTemplateResponse create(@Valid @RequestBody QuestionTemplateRequest request) {
        return service.create(request);
    }

    @GetMapping
    public Page<QuestionTemplateResponse> list(@RequestParam(required = false) TemplateStatus status,
                                               @PageableDefault(size = 20) Pageable pageable) {
        return service.list(status, pageable);
    }

    @GetMapping("/{id}")
    public QuestionTemplateResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public QuestionTemplateResponse update(@PathVariable UUID id,
                                           @Valid @RequestBody QuestionTemplateRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/publish")
    public QuestionTemplateResponse publish(@PathVariable UUID id) {
        return service.publish(id);
    }

    @DeleteMapping("/{id}")
    public QuestionTemplateResponse archive(@PathVariable UUID id) {
        return service.archive(id);
    }
}
