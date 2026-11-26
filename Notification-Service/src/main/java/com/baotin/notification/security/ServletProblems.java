package com.baotin.notification.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class ServletProblems {
    private ServletProblems() {}

    public static void write(HttpServletRequest request, HttpServletResponse response, int status, String code) throws IOException {
        Object existing = request.getAttribute("correlationId");
        String id = existing instanceof String value ? value : java.util.UUID.randomUUID().toString();
        response.setStatus(status);
        response.setHeader("X-Correlation-Id", id);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        response.getWriter().write("{\"type\":\"https://baotin.example/errors/" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')
            + "\",\"title\":\"Request rejected\",\"status\":" + status + ",\"code\":\"" + code + "\",\"correlationId\":\"" + id + "\"}");
    }
}
