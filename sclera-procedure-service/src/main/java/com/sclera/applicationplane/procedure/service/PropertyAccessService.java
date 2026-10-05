package com.sclera.applicationplane.procedure.service;

import com.sclera.applicationplane.procedure.authz.FgaAuthorizationService;
import com.sclera.applicationplane.procedure.dto.PropertyResponse;
import com.sclera.applicationplane.procedure.tenancy.PropertyLabels;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Which properties (VDMS) the caller may open — the list the property
 * switcher offers.
 *
 * There is no property service yet, so OpenFGA is the register: the
 * organization's property tuples say which properties exist, and the same
 * {@code can_view} check PropertyScopeFilter applies to X-Sclera-Property says
 * which of them this caller may open. The switcher therefore never offers a
 * property the filter would then refuse. A platform admin passes every check
 * and so sees all of them.
 *
 * With OpenFGA disabled there is nothing to read, so the configured labels
 * stand in for the register; every check allows then, so all of them show.
 */
@Service
public class PropertyAccessService {

    private final FgaAuthorizationService fga;
    private final PropertyLabels labels;

    public PropertyAccessService(FgaAuthorizationService fga, PropertyLabels labels) {
        this.fga = fga;
        this.labels = labels;
    }

    public List<PropertyResponse> viewable() {
        List<UUID> candidates = fga.isEnabled() ? fga.orgObjects("property") : labels.labelledIds();
        return candidates.stream()
                .distinct()
                .filter(id -> fga.check("property", id, "can_view"))
                .map(id -> new PropertyResponse(id, labels.codeFor(id)))
                .sorted(Comparator.comparing(PropertyResponse::code))
                .toList();
    }
}
