package com.sclera.applicationplane.procedure.tenancy;

import com.sclera.applicationplane.procedure.authz.FgaAuthorizationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Reads the property (VDMS) the caller is looking at from {@code
 * X-Sclera-Property} and, once it is allowed, puts it where the data layer can
 * find it.
 *
 * The organization is ambient — a JWT claim that becomes a schema — so the
 * property is ambient too, and both tiers of tenancy stay out of URLs. The
 * payoff is that one endpoint serves both views: no header is the organization
 * level, showing only procedures shared across it; a header narrows to that
 * property plus the shared ones.
 *
 * <strong>The header is never trusted.</strong> Anyone can send any id, so it
 * is checked against the caller's grants before it reaches the context, and a
 * caller without one gets 403 rather than a quietly empty list. Row-level
 * security is the layer behind this, not instead of it: this stops the request,
 * and RLS stops the rows if this is ever wrong.
 *
 * Runs after TenantProvisioningFilter, since the grant check needs OrgContext.
 * Deliberately not a {@code @Component} — registered in the security chain only,
 * for the same reason.
 */
public class PropertyScopeFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Sclera-Property";

    private static final Logger log = LoggerFactory.getLogger(PropertyScopeFilter.class);

    private final FgaAuthorizationService fga;

    public PropertyScopeFilter(FgaAuthorizationService fga) {
        this.fga = fga;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        try {
            if (header != null && !header.isBlank()) {
                UUID propertyId;
                try {
                    propertyId = UUID.fromString(header.trim());
                } catch (IllegalArgumentException e) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                            HEADER + " must be a property id");
                    return;
                }
                if (!fga.check("property", propertyId, "can_view")) {
                    // Deliberately the same answer whether the property exists
                    // and is someone else's or does not exist at all: telling
                    // the two apart would confirm another organization's
                    // property ids one guess at a time.
                    log.debug("Property {} refused for this caller", propertyId);
                    response.sendError(HttpServletResponse.SC_FORBIDDEN,
                            "No access to this property");
                    return;
                }
                PropertyContext.set(List.of(propertyId));
            }
            chain.doFilter(request, response);
        } finally {
            // The request thread goes back to the container's pool, so the
            // scope must not travel with it.
            PropertyContext.clear();
        }
    }
}
