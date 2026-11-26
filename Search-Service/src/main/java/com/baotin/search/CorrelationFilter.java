package com.baotin.search;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        UUID id;
        try { id=UUID.fromString(request.getHeader("X-Correlation-Id")); }
        catch (IllegalArgumentException | NullPointerException ignored) { id=UUID.randomUUID(); }
        request.setAttribute("correlationId",id.toString());response.setHeader("X-Correlation-Id",id.toString());
        chain.doFilter(request,response);
    }
}
