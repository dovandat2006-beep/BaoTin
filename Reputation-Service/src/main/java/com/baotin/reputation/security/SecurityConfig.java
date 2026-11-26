package com.baotin.reputation.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain threatReadSecurity(HttpSecurity http,
        @Value("${reputation.threat-read-token:}") String token) throws Exception {
        http.securityMatcher(request -> request.getRequestURI().startsWith("/internal/v1/reputation/")
                && request.getHeader("X-Service-Token") != null)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c -> c.disable())
            .requestCache(c -> c.disable())
            .addFilterBefore(new ReputationReadTokenFilter(token), AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                .requestMatchers(request -> "GET".equals(request.getMethod())
                    && request.getRequestURI().matches("/internal/v1/reputation/targets/[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
                    .hasAuthority("SCOPE_reputation.read")
                .anyRequest().denyAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) ->
                    ServletProblems.write(request, response, 401, "UNAUTHORIZED"))
                .accessDeniedHandler((request, response, ex) ->
                    ServletProblems.write(request, response, 403, "FORBIDDEN")));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${reputation.jwks-uri}") String jwks,
                          @Value("${reputation.issuer}") String issuer,
                          @Value("${reputation.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        var time = new JwtTimestampValidator(Duration.ofSeconds(30));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            time,
            new JwtIssuerValidator(issuer),
            token -> {
                boolean valid = "at+jwt".equals(token.getHeaders().get("typ"))
                    && token.getHeaders().get("kid") != null
                    && token.getAudience().contains(audience)
                    && "access".equals(token.getClaimAsString("token_use"))
                    && token.getIssuedAt() != null
                    && token.getExpiresAt() != null
                    && token.getNotBefore() != null
                    && token.getExpiresAt().isAfter(token.getIssuedAt())
                    && Duration.between(token.getIssuedAt(), token.getExpiresAt()).getSeconds() <= 600
                    && !token.getIssuedAt().isAfter(java.time.Instant.now().plusSeconds(30))
                    && token.getSubject() != null
                    && token.getId() != null
                    && token.getClaimAsString("sid") != null
                    && token.getClaim("ver") != null;
                return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);

        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c -> c.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                    "/actuator/health/readiness").permitAll()
                .requestMatchers("/internal/v1/reputation/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(resource -> resource.jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((request, response, e) ->
                    ServletProblems.write(request, response, 401, "UNAUTHORIZED")))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) ->
                    ServletProblems.write(request, response, 401, "UNAUTHORIZED"))
                .accessDeniedHandler((request, response, ex) ->
                    ServletProblems.write(request, response, 403, "FORBIDDEN")));
        return http.build();
    }
}
