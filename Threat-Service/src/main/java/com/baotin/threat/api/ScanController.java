package com.baotin.threat.api;

import com.baotin.threat.application.ScanService;
import com.baotin.threat.domain.ScanResult;
import com.baotin.threat.infrastructure.ScanMetricsStore;
import com.baotin.threat.infrastructure.ScanStore;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/scans")
public class ScanController {
    private final ScanService scans;
    private final ScanStore history;
    private final RateLimits limits;
    private final ScanMetricsStore metrics;
    public ScanController(ScanService scans, ScanStore history, RateLimits limits, ScanMetricsStore metrics) {
        this.scans = scans; this.history = history; this.limits = limits; this.metrics = metrics;
    }
    public record TextInput(@NotBlank @Size(max = 10000) String content) {}
    public record UrlInput(@NotBlank @Size(max = 4096) String url) {}
    public record ScanInput(@NotBlank @Pattern(regexp = "TEXT|SMS|URL") String type, @NotBlank @Size(max = 10000) String content) {}
    @PostMapping("/text")
    public ScanResult text(@Valid @RequestBody TextInput input, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request, HttpServletResponse response) {
        return run("TEXT", input.content(), jwt, request, response);
    }
    @PostMapping("/url")
    public ScanResult url(@Valid @RequestBody UrlInput input, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request, HttpServletResponse response) {
        return run("URL", input.url(), jwt, request, response);
    }
    @PostMapping
    public ScanResult compatible(@Valid @RequestBody ScanInput input, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request, HttpServletResponse response) {
        return run(input.type().equals("SMS") ? "TEXT" : input.type(), input.content(), jwt, request, response);
    }
    private ScanResult run(String type, String content, Jwt jwt, HttpServletRequest request, HttpServletResponse response) {
        UUID user = user(jwt); limits.check(user, request);
        response.setHeader("Cache-Control", "no-store");
        return scans.scan(type, content, user, UUID.fromString(response.getHeader("X-Correlation-Id")));
    }
    @GetMapping("/admin/stats")
    @PreAuthorize("hasAnyRole('MODERATOR','ADMIN')")
    public ScanMetricsStore.ScanStats adminStats() {
        return metrics.today();
    }

    @GetMapping("/history")
    public ScanStore.HistoryPage history(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String type,
                                         @RequestParam(required = false) String riskLevel, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "createdAt,desc") String sort) {
        if (page < 0 || page > 100000 || size < 1 || size > 100 || (type != null && !Set.of("TEXT", "URL").contains(type))
                || (riskLevel != null && !Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(riskLevel)) || !Set.of("createdAt,asc", "createdAt,desc").contains(sort)) throw new IllegalArgumentException("INVALID_HISTORY_FILTER");
        return history.history(user(jwt), type, riskLevel, page, size, sort.endsWith("asc"));
    }
    @GetMapping("/history/{id}")
    public ScanResult detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return history.detail(user(jwt), id); }
    @DeleteMapping("/history/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { history.delete(user(jwt), id); }
    @DeleteMapping("/history") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAll(@AuthenticationPrincipal Jwt jwt) { history.deleteAll(user(jwt)); }
    private UUID user(Jwt jwt) { return jwt == null ? null : UUID.fromString(jwt.getSubject()); }
}
