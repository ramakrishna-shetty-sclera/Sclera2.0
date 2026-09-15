package com.sclera.applicationplane.inspection.controller;

import com.sclera.applicationplane.inspection.dto.ChecklistDtos.ChecklistResponse;
import com.sclera.applicationplane.inspection.dto.ReactiveServiceDtos.CreateReactiveServiceRequest;
import com.sclera.applicationplane.inspection.dto.ReactiveServiceDtos.RaiseRequestRequest;
import com.sclera.applicationplane.inspection.dto.ReactiveServiceDtos.ReactiveServiceResponse;
import com.sclera.applicationplane.inspection.service.ReactiveServiceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Reactive services: a checklist associated with a location, raised on demand
 * by scanning the service's QR code. Raised requests are checklists with
 * source=REACTIVE_SERVICE.
 */
@RestController
@RequestMapping("/api/v1/reactive-services")
public class ReactiveServiceController {

    private final ReactiveServiceService service;

    public ReactiveServiceController(ReactiveServiceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReactiveServiceResponse create(@Valid @RequestBody CreateReactiveServiceRequest request) {
        return service.create(request);
    }

    @GetMapping
    public List<ReactiveServiceResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ReactiveServiceResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Scan resolution: QR token → service. */
    @GetMapping("/by-token/{qrToken}")
    public ReactiveServiceResponse resolveByToken(@PathVariable String qrToken) {
        return service.resolveByToken(qrToken);
    }

    @PostMapping("/{id}/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ChecklistResponse raiseRequest(@PathVariable UUID id,
                                          @Valid @RequestBody RaiseRequestRequest request) {
        return service.raiseRequest(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
