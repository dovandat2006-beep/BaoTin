package com.baotin.report.api;

import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ReportErrors {
    @ExceptionHandler(ReportFailure.class)
    ResponseEntity<ProblemDetail> failure(ReportFailure e,HttpServletRequest request) { return problem(e.status,e.code,e.getMessage(),request); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> size(Exception e,HttpServletRequest request) { return problem(413,"EVIDENCE_SIZE","Tối đa 10 MB mỗi file.",request); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HandlerMethodValidationException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class,MissingRequestHeaderException.class,MissingServletRequestPartException.class})
    ResponseEntity<ProblemDetail> invalid(Exception e,HttpServletRequest request) { return problem(400,"INVALID_INPUT","Kiểm tra dữ liệu, khóa gửi và kích thước trang.",request); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<ProblemDetail> database(Exception e,HttpServletRequest request) { return problem(503,"REPORT_UNAVAILABLE","Dịch vụ báo cáo chưa sẵn sàng. Hãy thử lại.",request); }
    private ResponseEntity<ProblemDetail> problem(int status,String code,String message,HttpServletRequest request) {
        var detail=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),message);detail.setTitle(message);detail.setProperty("code",code);
        String correlation=request.getHeader("X-Correlation-Id");try {correlation=UUID.fromString(correlation).toString();}catch(Exception e){correlation=UUID.randomUUID().toString();}
        detail.setProperty("correlationId",correlation);
        return ResponseEntity.status(status).header("X-Correlation-Id",correlation).header("Cache-Control","no-store").body(detail);
    }
}
