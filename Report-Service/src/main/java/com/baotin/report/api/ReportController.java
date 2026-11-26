package com.baotin.report.api;

import com.baotin.report.application.ReportService;
import com.baotin.report.api.ReportModels.*;
import com.baotin.report.domain.ReportStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.InputStreamResource;

@RestController
public class ReportController {
    private final ReportService service;
    public ReportController(ReportService service) { this.service=service; }
    private static UUID user(Authentication auth) { return UUID.fromString(auth.getName()); }
    private static boolean moderator(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a->Set.of("ROLE_MODERATOR","ROLE_ADMIN").contains(a.getAuthority())); }
    private static String correlation(String value) { try { return UUID.fromString(value).toString(); } catch(Exception e) { return UUID.randomUUID().toString(); } }
    @PostMapping("/api/v1/reports") @ResponseStatus(HttpStatus.CREATED)
    public Report create(Authentication auth,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Create input,@RequestHeader(value="X-Correlation-Id",required=false) String correlation) {
        return service.create(user(auth),key,input,correlation(correlation));
    }
    @GetMapping("/api/v1/reports/me")
    public Page<Report> mine(Authentication auth,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.mine(user(auth),page,size); }
    @GetMapping("/api/v1/reports/{id}")
    public Report detail(Authentication auth,@PathVariable UUID id) { return service.detail(id,user(auth),false); }
    @PostMapping(value="/api/v1/reports/{id}/evidence",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public Evidence upload(Authentication auth,@PathVariable UUID id,@RequestPart("file") MultipartFile file) throws java.io.IOException {
        if(file.getSize()>com.baotin.report.domain.EvidencePolicy.MAX_BYTES) throw new ReportFailure(413,"EVIDENCE_SIZE","Tối đa 10 MB mỗi file.");
        return service.upload(id,user(auth),file.getBytes(),file.getContentType(),file.getOriginalFilename());
    }
    @DeleteMapping("/api/v1/reports/{id}/evidence/{evidenceId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth,@PathVariable UUID id,@PathVariable UUID evidenceId) { service.deleteEvidence(id,evidenceId,user(auth)); }
    @GetMapping({"/api/v1/reports/{id}/evidence/{evidenceId}","/api/v1/moderation/reports/{id}/evidence/{evidenceId}"})
    public ResponseEntity<InputStreamResource> download(Authentication auth,@PathVariable UUID id,@PathVariable UUID evidenceId,jakarta.servlet.http.HttpServletRequest request) {
        boolean review=request.getRequestURI().startsWith("/api/v1/moderation/");
        var result=service.download(id,evidenceId,user(auth),review && moderator(auth));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.evidence().mimeType())).contentLength(result.evidence().sizeBytes())
            .header("Content-Disposition",ContentDisposition.attachment().filename(result.evidence().originalFilename(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(new InputStreamResource(result.stream()));
    }
    @PostMapping("/api/v1/reports/{id}/resubmit")
    public Report supplement(Authentication auth,@PathVariable UUID id,@Valid @RequestBody Supplement input) { return service.supplement(id,user(auth),input); }
    @GetMapping("/api/v1/moderation/reports")
    public Page<Report> queue(@RequestParam(defaultValue="SUBMITTED") String status,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.queue(status,page,size); }
    @GetMapping("/api/v1/moderation/reports/{id}")
    public Report reviewDetail(Authentication auth,@PathVariable UUID id) { return service.detail(id,user(auth),true); }
    @PostMapping("/api/v1/moderation/reports/{id}/{action}")
    public Report decide(Authentication auth,@PathVariable UUID id,@PathVariable String action,@Valid @RequestBody Decision decision,@RequestHeader(value="X-Correlation-Id",required=false) String correlation) {
        ReportStatus next=switch(action) {
            case "start-review"->ReportStatus.PENDING_REVIEW;case "verify"->ReportStatus.VERIFIED;
            case "reject"->ReportStatus.REJECTED;case "request-info"->ReportStatus.NEED_MORE_INFO;
            default->throw ReportFailure.missing();
        };
        return service.review(id,user(auth),next,decision,correlation(correlation));
    }
}
