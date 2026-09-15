package com.sclera.applicationplane.helper.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Runs AFTER Spring Security (OrgContext is populated by ScleraJwtConverter
 * during authentication) and BEFORE controllers: makes sure the caller's
 * tenant schema exists and is migrated before any repository call touches it.
 *
 * Deliberately NOT a @Component â€” registered inside the security chain only,
 * so Boot doesn't also mount it globally (where OrgContext would be empty).
 */
public class TenantProvisioningFilter extends OncePerRequestFilter {

    private final TenantRegistryService registry;

    public TenantProvisioningFilter(TenantRegistryService registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        UUID orgId = TenantContext.currentOrgId();
        if (orgId != null) {
            registry.ensureTenant(orgId);
        }
        chain.doFilter(request, response);
    }
}

