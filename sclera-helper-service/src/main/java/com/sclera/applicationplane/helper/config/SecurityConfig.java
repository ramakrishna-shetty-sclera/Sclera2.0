package com.sclera.applicationplane.helper.config;

import com.sclera.applicationplane.helper.tenancy.TenantProvisioningFilter;
import com.sclera.applicationplane.helper.tenancy.TenantRegistryService;
import com.sclera.controlplane.common.filter.MdcFilter;
import com.sclera.controlplane.common.security.InternalEndpointFilter;
import com.sclera.controlplane.common.security.ScleraJwtConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // @PreAuthorize on controllers, backed by the OpenFGA "fga" bean
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   TenantRegistryService tenantRegistry) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/internal/**").permitAll()   // protected by HMAC + InternalEndpointFilter
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(scleraJwtConverter())))
                .addFilterBefore(mdcFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(internalEndpointFilter(), UsernamePasswordAuthenticationFilter.class)
                // After authorization: OrgContext is populated, provision the tenant
                // schema before any repository call. Plain instance (not a bean) so
                // Boot doesn't also mount it outside the security chain.
                .addFilterAfter(new TenantProvisioningFilter(tenantRegistry), AuthorizationFilter.class)
                .build();
    }

    @Bean public ScleraJwtConverter scleraJwtConverter() { return new ScleraJwtConverter(); }
    @Bean public MdcFilter mdcFilter() { return new MdcFilter(); }
    @Bean public InternalEndpointFilter internalEndpointFilter() { return new InternalEndpointFilter(); }
}
