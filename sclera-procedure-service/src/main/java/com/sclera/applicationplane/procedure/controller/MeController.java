package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.dto.PropertyResponse;
import com.sclera.applicationplane.procedure.service.PropertyAccessService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Answers about the caller rather than about a resource. Responses are wrapped
 * in the standard envelope by sclera-common's ResponseEnvelopeAdvice.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final PropertyAccessService properties;

    public MeController(PropertyAccessService properties) {
        this.properties = properties;
    }

    /**
     * The properties the caller may open, sorted by code. Empty means the
     * caller works at organization level only. The property switcher lists
     * these and sends the chosen id back as X-Sclera-Property.
     */
    @GetMapping("/properties")
    @PreAuthorize("@fga.checkOrg('can_view')")
    public List<PropertyResponse> properties() {
        return properties.viewable();
    }
}
