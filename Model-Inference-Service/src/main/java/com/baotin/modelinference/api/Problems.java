package com.baotin.modelinference.api;

import com.baotin.modelinference.application.ModelRegistry.InferenceFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestControllerAdvice
public class Problems {
    public static Map<String, Object> body(int status, String code, String correlation) {
        return Map.of("type", "https://baotin.example/errors/" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                "title", "Request could not be completed", "status", status, "code", code, "correlationId", correlation);
    }
    public static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(JsonMapper.builder().build().writeValueAsString(body(status, code, response.getHeader("X-Correlation-Id"))));
    }
    @ExceptionHandler(InferenceFailure.class)
    ResponseEntity<?> inference(InferenceFailure e, HttpServletResponse response) { return error(e.status, e.getMessage(), response); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e, HttpServletResponse response) { return error(400, "INVALID_INFERENCE_INPUT", response); }
    private ResponseEntity<?> error(int status, String code, HttpServletResponse response) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body(status, code, response.getHeader("X-Correlation-Id")));
    }
}
