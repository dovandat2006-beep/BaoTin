package com.baotin.reputation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Created only inside the private service chain, never auto-registered globally. */
final class ReputationReadTokenFilter extends OncePerRequestFilter {
    private final byte[] expected;

    ReputationReadTokenFilter(String token) {
        if (!token.isEmpty() && !token.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid Reputation read-key configuration");
        }
        expected = token.getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var values = Collections.list(request.getHeaders("X-Service-Token"));
        boolean valid = expected.length != 0 && values.size() == 1
            && request.getHeader("Authorization") == null
            && values.getFirst().matches("[a-f0-9]{64}")
            && MessageDigest.isEqual(expected, values.getFirst().getBytes(StandardCharsets.US_ASCII));
        if (!valid) {
            ServletProblems.write(request, response, 401, "UNAUTHORIZED");
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("threat-service", null,
            List.of(new SimpleGrantedAuthority("SCOPE_reputation.read"))));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
