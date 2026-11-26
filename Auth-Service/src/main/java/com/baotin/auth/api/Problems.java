package com.baotin.auth.api;

import java.net.URI;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class Problems {
    public static ProblemDetail problem(int status,String code,String detail,String path) {
        var p=ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status),detail);
        p.setType(URI.create("https://baotin.example/errors/"+code.toLowerCase(java.util.Locale.ROOT).replace('_','-')));
        p.setProperty("code",code);p.setInstance(URI.create(path));return p;
    }
    private ProblemDetail requestProblem(int status,String code,String detail,HttpServletRequest request){
        var p=problem(status,code,detail,request.getRequestURI());Object correlation=request.getAttribute("correlationId");if(correlation instanceof String)p.setProperty("correlationId",correlation);return p;
    }
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ProblemDetail> failure(ApiFailure e,HttpServletRequest request){
        return ResponseEntity.status(e.status()).body(requestProblem(e.status(),e.code(),e.getMessage(),request));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,IllegalArgumentException.class})
    ResponseEntity<ProblemDetail> validation(Exception e,HttpServletRequest request){
        return ResponseEntity.badRequest().body(requestProblem(400,"INVALID_INPUT","Invalid request fields",request));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(Exception e,HttpServletRequest request){
        return ResponseEntity.status(409).body(requestProblem(409,"IDENTITY_CONFLICT","Identity is not available",request));
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(Exception e,HttpServletRequest request){
        return ResponseEntity.status(403).body(requestProblem(403,"FORBIDDEN","Insufficient permission",request));
    }
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> methodValidation(Exception e,HttpServletRequest request){
        return ResponseEntity.badRequest().body(requestProblem(400,"INVALID_INPUT","Invalid request fields",request));
    }
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missingParameter(Exception e,HttpServletRequest request){
        return ResponseEntity.badRequest().body(requestProblem(400,"INVALID_INPUT","Required request parameter missing",request));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e,HttpServletRequest request){
        return ResponseEntity.internalServerError().body(requestProblem(500,"INTERNAL_ERROR","Request could not be completed",request));
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(Exception e,HttpServletRequest request){return ResponseEntity.status(405).body(requestProblem(405,"METHOD_NOT_ALLOWED","HTTP method not allowed",request));}
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> media(Exception e,HttpServletRequest request){return ResponseEntity.status(415).body(requestProblem(415,"UNSUPPORTED_MEDIA_TYPE","Use application/json",request));}
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> missing(Exception e,HttpServletRequest request){return ResponseEntity.status(404).body(requestProblem(404,"NOT_FOUND","Resource not found",request));}
}
