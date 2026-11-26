package com.baotin.threat.config;

import com.baotin.threat.api.Problems;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration
@EnableScheduling
@EnableMethodSecurity
public class ScanConfiguration {
    @Bean JwtDecoder decoder(@Value("${scan.jwks-uri}") String jwks, @Value("${scan.jwt-issuer}") String issuer, @Value("${scan.jwt-audience}") String audience) {
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(1000); factory.setReadTimeout(1000);
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).restOperations(new RestTemplate(factory)).jwsAlgorithm(SignatureAlgorithm.RS256).validateType(false).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(30)), new JwtIssuerValidator(issuer), jwt -> {
            boolean valid;
            try {
                UUID.fromString(jwt.getSubject()); UUID.fromString(jwt.getClaimAsString("sid"));
                valid = "at+jwt".equals(jwt.getHeaders().get("typ")) && jwt.getHeaders().get("kid") != null && jwt.getAudience().contains(audience)
                        && "access".equals(jwt.getClaimAsString("token_use")) && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null && jwt.getNotBefore() != null
                        && jwt.getExpiresAt().isAfter(jwt.getIssuedAt()) && Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds() <= 600
                        && !jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(30)) && jwt.getId() != null && jwt.getClaim("ver") instanceof Number;
            } catch (RuntimeException e) { valid = false; }
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }));
        return decoder;
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return http.csrf(c -> c.disable()).cors(c -> c.disable()).httpBasic(c -> c.disable()).formLogin(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/scans", "/api/v1/scans/text", "/api/v1/scans/url").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/scans/admin/stats").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/scans/history", "/api/v1/scans/history/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/scans/history", "/api/v1/scans/history/*").authenticated().anyRequest().denyAll())
                .oauth2ResourceServer(r -> r.jwt(j -> j.jwtAuthenticationConverter(converter)).authenticationEntryPoint((q, s, e) -> { s.setHeader("WWW-Authenticate", "Bearer"); Problems.write(s, 401, "UNAUTHORIZED"); }))
                .exceptionHandling(e -> e.authenticationEntryPoint((q, s, x) -> Problems.write(s, 401, "UNAUTHORIZED")).accessDeniedHandler((q, s, x) -> Problems.write(s, 403, "FORBIDDEN")))
                .addFilterBefore(new BoundaryFilter(), BearerTokenAuthenticationFilter.class).build();
    }
    private static class BoundaryFilter extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            UUID correlation;
            try { correlation = UUID.fromString(request.getHeader("X-Correlation-Id")); } catch (RuntimeException e) { correlation = UUID.randomUUID(); }
            response.setHeader("X-Correlation-Id", correlation.toString()); response.setHeader("Cache-Control", "no-store");
            if (!request.getMethod().equals("POST")) { chain.doFilter(request, response); return; }
            byte[] body = request.getInputStream().readNBytes(65537);
            if (body.length > 65536) { Problems.write(response, 413, "INPUT_TOO_LARGE"); return; }
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input = new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous API"); }
                    };
                }
            }, response);
        }
    }
}
