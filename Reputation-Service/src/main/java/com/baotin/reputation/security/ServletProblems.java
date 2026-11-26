package com.baotin.reputation.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

public final class ServletProblems {
    private ServletProblems() {
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, int status, String code)
        throws IOException {
        Object existing = request.getAttribute(CorrelationFilter.ATTRIBUTE);
        String id = existing instanceof String value ? value : UUID.randomUUID().toString();
        response.setStatus(status);
        response.setHeader("X-Correlation-Id", id);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        if (status == 401) {
            response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        }
        response.getWriter().write("{\"type\":\"https://baotin.example/errors/"
            + code.toLowerCase(Locale.ROOT).replace('_', '-')
            + "\",\"title\":\"Request rejected\",\"status\":" + status + ",\"code\":\"" + code
            + "\",\"correlationId\":\"" + id + "\"}");
    }
}
