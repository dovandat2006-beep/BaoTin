package com.baotin.threat.api;

import com.baotin.threat.application.TargetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

@RestController
public class TargetController {
    public record Resolve(@NotNull @Pattern(regexp="URL|MESSAGE_TEMPLATE") String type,@NotBlank @Size(max=10000) String value,
        java.util.UUID scanId,java.util.UUID reporterUserId) {}
    private final TargetService service;
    public TargetController(TargetService service) {this.service=service;}
    @PostMapping("/internal/v1/targets/resolve")
    public ResponseEntity<?> resolve(@Valid @RequestBody Resolve input,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        if(Boolean.TRUE.equals(request.getAttribute("baotin.targetCaller.notification")) &&
            (!"URL".equals(input.type()) || input.scanId()!=null || input.reporterUserId()!=null))
        {
            String correlation=java.util.UUID.randomUUID().toString();response.setHeader("X-Correlation-Id",correlation);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(Problems.body(403,"TARGET_CALLER_FORBIDDEN",correlation));
        }
        return ResponseEntity.ok(service.resolve(input.type(),input.value(),input.scanId(),input.reporterUserId()));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail invalid() {return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Invalid target");}
}
