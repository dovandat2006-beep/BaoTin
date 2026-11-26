package com.baotin.reputation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String supplied = request.getHeader("X-Correlation-Id");
        String correlation;
        try {
            correlation = supplied == null ? UUID.randomUUID().toString() : UUID.fromString(supplied).toString();
        } catch (IllegalArgumentException e) {
            correlation = UUID.randomUUID().toString();
        }
        request.setAttribute(ATTRIBUTE, correlation);
        response.setHeader("X-Correlation-Id", correlation);
        chain.doFilter(request, response);
    }
}
