package com.baotin.analytics.api;

import com.baotin.analytics.integration.UpstreamUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiProblems {

    private static final Logger log = LoggerFactory.getLogger(ApiProblems.class);

    public static void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code) throws IOException {
        Object existing = request.getAttribute("correlationId");
        String correlationId = existing instanceof String value && !value.isBlank()
                ? value
                : UUID.randomUUID().toString();
        response.setStatus(status);
        response.setHeader("X-Correlation-Id", correlationId);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == 401) {
            response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        }
        response.getWriter().write("{\"type\":\"" + type(code)
                + "\",\"title\":\"Request rejected\",\"status\":" + status
                + ",\"code\":\"" + code + "\",\"correlationId\":\"" + correlationId + "\"}");
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<ProblemDetail> invalidInput(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Invalid request fields", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permission", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> notFound(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request);
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    ResponseEntity<ProblemDetail> upstreamUnavailable(
            UpstreamUnavailableException exception,
            HttpServletRequest request) {
        log.warn("Analytics upstream unavailable: upstream={} correlationId={}",
                exception.upstream(),
                correlationIdOrUnknown(request));
        return response(HttpStatus.SERVICE_UNAVAILABLE, "ANALYTICS_UPSTREAM_UNAVAILABLE",
                "Analytics data source unavailable", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Request could not be completed", request);
    }

    private static String correlationIdOrUnknown(HttpServletRequest request) {
        Object correlation = request.getAttribute("correlationId");
        return correlation instanceof String value && !value.isBlank() ? value : "unknown";
    }

    private static ResponseEntity<ProblemDetail> response(
            HttpStatusCode status,
            String code,
            String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type(code)));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        Object correlation = request.getAttribute("correlationId");
        if (correlation instanceof String value && !value.isBlank()) {
            problem.setProperty("correlationId", value);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static String type(String code) {
        return "https://baotin.example/errors/" + code.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
