package com.baotin.reputation.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class Problems {
    private static final Logger log = LoggerFactory.getLogger(Problems.class);

    public static ProblemResponse problem(int status, String code, String detail, String path, String correlationId) {
        return new ProblemResponse(
            URI.create("https://baotin.example/errors/" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')),
            HttpStatus.valueOf(status).getReasonPhrase(), status, code, detail, URI.create(path), correlationId);
    }

    private ResponseEntity<ProblemResponse> response(int status, String code, String detail, HttpServletRequest request) {
        Object correlation = request.getAttribute("correlationId");
        String id = correlation instanceof String value ? value : UUID.randomUUID().toString();
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem(status, code, detail, request.getRequestURI(), id));
    }

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ProblemResponse> failure(ApiFailure e, HttpServletRequest request) {
        return response(e.status(), e.code(), e.getMessage(), request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        IllegalArgumentException.class, HandlerMethodValidationException.class,
        MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemResponse> validation(Exception e, HttpServletRequest request) {
        return response(400, "INVALID_INPUT", "Invalid request fields", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemResponse> conflict(Exception e, HttpServletRequest request) {
        return response(409, "CONFLICT", "Request conflicts with current state", request);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<ProblemResponse> denied(Exception e, HttpServletRequest request) {
        return response(403, "FORBIDDEN", "Insufficient permission", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemResponse> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected API failure type={} correlationId={}", e.getClass().getSimpleName(),
            request.getAttribute("correlationId"));
        return response(500, "INTERNAL_ERROR", "Request could not be completed", request);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemResponse> method(Exception e, HttpServletRequest request) {
        return response(405, "METHOD_NOT_ALLOWED", "HTTP method not allowed", request);
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemResponse> media(Exception e, HttpServletRequest request) {
        return response(415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json", request);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ProblemResponse> missing(Exception e, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "Resource not found", request);
    }
}
