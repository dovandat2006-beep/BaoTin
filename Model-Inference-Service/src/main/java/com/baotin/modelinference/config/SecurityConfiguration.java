package com.baotin.modelinference.config;

import com.baotin.modelinference.api.Problems;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
    @Bean SecurityFilterChain security(HttpSecurity http, @Value("${model.service-token:}") String token) throws Exception {
        if (!token.isBlank() && (token.length() < 32 || token.length() > 256 || token.chars().anyMatch(Character::isWhitespace))) throw new IllegalArgumentException("Service token must contain 32-256 non-whitespace characters");
        return http.csrf(c -> c.disable()).cors(c -> c.disable()).httpBasic(c -> c.disable()).formLogin(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers("/internal/v1/**").hasRole("INFERENCE_CLIENT").anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((q, r, x) -> Problems.write(r, 401, "UNAUTHORIZED"))
                        .accessDeniedHandler((q, r, x) -> Problems.write(r, 403, "FORBIDDEN")))
                .addFilterBefore(new BoundaryFilter(token), UsernamePasswordAuthenticationFilter.class).build();
    }

    private static final class BoundaryFilter extends OncePerRequestFilter {
        private final byte[] expected;
        BoundaryFilter(String token) { expected = token.isBlank() ? new byte[0] : ("Bearer " + token).getBytes(StandardCharsets.UTF_8); }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            String correlation = request.getHeader("X-Correlation-Id");
            response.setHeader("X-Correlation-Id", correlation != null && correlation.matches("[A-Za-z0-9._-]{1,100}") ? correlation : UUID.randomUUID().toString());
            if (request.getRequestURI().startsWith("/internal/")) {
                String value = request.getHeader("Authorization");
                if (expected.length == 0 || value == null || !MessageDigest.isEqual(expected, value.getBytes(StandardCharsets.UTF_8))) {
                    response.setHeader("WWW-Authenticate", "Bearer"); Problems.write(response, 401, "UNAUTHORIZED"); return;
                }
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("threat-service", null, List.of(new SimpleGrantedAuthority("ROLE_INFERENCE_CLIENT"))));
            }
            if (request.getMethod().equals("POST")) {
                byte[] body = request.getInputStream().readNBytes(65537);
                if (body.length > 65536) { Problems.write(response, 413, "INPUT_TOO_LARGE"); return; }
                var bounded = new HttpServletRequestWrapper(request) {
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
                };
                chain.doFilter(bounded, response);
            } else chain.doFilter(request, response);
        }
    }
}
