package com.baotin.threat.api;

import com.baotin.threat.application.ScanService.ScanBusy;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.json.JsonMapper;

@RestControllerAdvice
public class Problems {
    public static Map<String, Object> body(int status, String code, String correlation) {
        return Map.of("type", "https://baotin.example/errors/" + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                "title", "Request could not be completed", "status", status, "code", code, "correlationId", correlation == null ? UUID.randomUUID().toString() : correlation);
    }
    public static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(JsonMapper.builder().build().writeValueAsString(body(status, code, response.getHeader("X-Correlation-Id"))));
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception e, HttpServletResponse response) { return error(400, "INVALID_SCAN_INPUT", response); }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<?> missing(Exception e, HttpServletResponse response) { return error(404, "SCAN_NOT_FOUND", response); }
    @ExceptionHandler({ScanBusy.class, RateLimits.Exceeded.class})
    ResponseEntity<?> busy(Exception e, HttpServletResponse response) {
        response.setHeader("Retry-After", "60"); return error(429, e instanceof ScanBusy ? "SCAN_BUSY" : "SCAN_RATE_LIMITED", response);
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(Exception e, HttpServletResponse response) { return error(503, "SCAN_STORAGE_UNAVAILABLE", response); }
    private ResponseEntity<?> error(int status, String code, HttpServletResponse response) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body(status, code, response.getHeader("X-Correlation-Id")));
    }
}
