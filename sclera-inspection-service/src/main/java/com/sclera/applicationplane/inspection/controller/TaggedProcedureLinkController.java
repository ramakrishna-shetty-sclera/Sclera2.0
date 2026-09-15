package com.sclera.applicationplane.inspection.controller;

import com.sclera.applicationplane.inspection.domain.TargetType;
import com.sclera.applicationplane.inspection.dto.ChecklistDtos.ChecklistResponse;
import com.sclera.applicationplane.inspection.dto.TaggedProcedureLinkDtos.CreateLinkRequest;
import com.sclera.applicationplane.inspection.dto.TaggedProcedureLinkDtos.FillRequest;
import com.sclera.applicationplane.inspection.dto.TaggedProcedureLinkDtos.OneTimeRequest;
import com.sclera.applicationplane.inspection.dto.TaggedProcedureLinkDtos.TaggedProcedureLinkResponse;
import com.sclera.applicationplane.inspection.service.TaggedProcedureLinkService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Tagged procedures on managed assets/locations. Two modes per the VDMS doc:
 * a persistent reusable TEMPLATE link (tagged at Procedure level; /fill spawns
 * a fresh checklist each time), and ONE-TIME (added on the asset/location;
 * creates a single checklist, stores nothing).
 */
@RestController
@RequestMapping("/api/v1/tagged-procedures")
public class TaggedProcedureLinkController {

    private final TaggedProcedureLinkService service;

    public TaggedProcedureLinkController(TaggedProcedureLinkService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaggedProcedureLinkResponse create(@Valid @RequestBody CreateLinkRequest request) {
        return service.create(request);
    }

    @GetMapping
    public List<TaggedProcedureLinkResponse> list(@RequestParam(required = false) TargetType targetType,
                                                  @RequestParam(required = false) UUID targetId) {
        return service.list(targetType, targetId);
    }

    @PostMapping("/{id}/fill")
    @ResponseStatus(HttpStatus.CREATED)
    public ChecklistResponse fill(@PathVariable UUID id,
                                  @Valid @RequestBody(required = false) FillRequest request) {
        return service.fill(id, request);
    }

    @PostMapping("/one-time")
    @ResponseStatus(HttpStatus.CREATED)
    public ChecklistResponse oneTime(@Valid @RequestBody OneTimeRequest request) {
        return service.oneTime(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
