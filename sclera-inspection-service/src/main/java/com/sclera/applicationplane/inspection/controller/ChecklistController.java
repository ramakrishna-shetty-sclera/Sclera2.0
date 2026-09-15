package com.sclera.applicationplane.inspection.controller;

import com.sclera.applicationplane.inspection.domain.ChecklistStatus;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.AssigneeRequest;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.ChecklistResponse;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.ExceptionRequest;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.GenerateRequest;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.SaveAnswersRequest;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.WorkOrderRequest;
import com.sclera.applicationplane.inspection.service.ChecklistService;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.UUID;

/**
 * Checklist lifecycle API. Checklists are generated from a configured
 * inspection's tagged procedures, then filled through the To-Do → Complete
 * (or Exception / Incomplete / Failed) states.
 */
@RestController
@RequestMapping("/api/v1/checklists")
public class ChecklistController {

    private final ChecklistService service;

    public ChecklistController(ChecklistService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ChecklistResponse> generate(@Valid @RequestBody GenerateRequest request) {
        return service.generate(request);
    }

    @GetMapping
    public List<ChecklistResponse> list(@RequestParam(required = false) UUID configId,
                                        @RequestParam(required = false) ChecklistStatus status) {
        return service.list(configId, status);
    }

    @GetMapping("/{id}")
    public ChecklistResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/check-in")
    public ChecklistResponse checkIn(@PathVariable UUID id) {
        return service.checkIn(id);
    }

    @PutMapping("/{id}/answers")
    public ChecklistResponse saveAnswers(@PathVariable UUID id,
                                         @Valid @RequestBody SaveAnswersRequest request) {
        return service.saveAnswers(id, request);
    }

    @PostMapping("/{id}/submit")
    public ChecklistResponse submit(@PathVariable UUID id,
                                    @Valid @RequestBody(required = false) SaveAnswersRequest request) {
        return service.submit(id, request);
    }

    @PostMapping("/{id}/exception")
    public ChecklistResponse addException(@PathVariable UUID id,
                                          @Valid @RequestBody ExceptionRequest request) {
        return service.addException(id, request.reason());
    }

    @PostMapping("/{id}/reopen")
    public ChecklistResponse reopen(@PathVariable UUID id) {
        return service.reopen(id);
    }

    @PostMapping("/{id}/mark-incomplete")
    public ChecklistResponse markIncomplete(@PathVariable UUID id) {
        return service.markIncomplete(id);
    }

    @PutMapping("/{id}/assignee")
    public ChecklistResponse updateAssignee(@PathVariable UUID id,
                                            @Valid @RequestBody AssigneeRequest request) {
        return service.updateAssignee(id, request.assigneeEmail());
    }

    @PostMapping("/{id}/work-order")
    @ResponseStatus(HttpStatus.CREATED)
    public ChecklistResponse createWorkOrder(@PathVariable UUID id,
                                             @RequestBody(required = false) WorkOrderRequest request) {
        return service.createWorkOrder(id, request != null ? request.note() : null);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
