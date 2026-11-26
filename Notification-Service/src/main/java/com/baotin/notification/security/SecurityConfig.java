package com.baotin.notification.security;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder(
        @Value("${notification.jwks-uri}") String jwks,
        @Value("${notification.issuer}") String issuer,
        @Value("${notification.audience}") String audience) {
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

    @Bean SecurityFilterChain security(HttpSecurity http, @Value("${notification.allowed-origins}") String origins) throws Exception {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins(origins));
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-CSRF-TOKEN"));
        cors.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);

        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);

        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c -> c.disable())
            .cors(c -> c.configurationSource(source))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .requestMatchers("/api/v1/notifications/**", "/api/v1/watchlist/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(resource -> resource.jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((request, response, e) -> ServletProblems.write(request, response, 401, "UNAUTHORIZED")))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) -> ServletProblems.write(request, response, 401, "UNAUTHORIZED"))
                .accessDeniedHandler((request, response, ex) -> ServletProblems.write(request, response, 403, "FORBIDDEN")));
        return http.build();
    }

    /** Exact origins only; local HTTP is allowed for development, public origins must be HTTPS. */
    static List<String> origins(String configured) {
        List<String> result = new ArrayList<>();
        for (String value : configured.split(",", -1)) {
            String origin = value.trim();
            URI uri = URI.create(origin);
            boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
            if (uri.getHost() == null || origin.contains("*") || uri.getUserInfo() != null || !uri.getPath().isEmpty()
                || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) {
                throw new IllegalArgumentException("Exact HTTPS origins required; local HTTP is development-only");
            }
            if (!result.contains(origin)) result.add(origin);
        }
        return List.copyOf(result);
    }
}
