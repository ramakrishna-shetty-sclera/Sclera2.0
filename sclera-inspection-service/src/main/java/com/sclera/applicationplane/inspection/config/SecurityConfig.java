package com.sclera.applicationplane.inspection.config;

import com.sclera.controlplane.common.filter.MdcFilter;
import com.sclera.controlplane.common.security.InternalEndpointFilter;
import com.sclera.controlplane.common.security.ScleraJwtConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
                .build();
    }

    @Bean public ScleraJwtConverter scleraJwtConverter() { return new ScleraJwtConverter(); }
    @Bean public MdcFilter mdcFilter() { return new MdcFilter(); }
    @Bean public InternalEndpointFilter internalEndpointFilter() { return new InternalEndpointFilter(); }
}
