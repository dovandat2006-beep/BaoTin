package com.baotin.analytics.api;

import com.baotin.analytics.application.AdminSummary;
import com.baotin.analytics.application.AnalyticsService;
import com.baotin.analytics.application.AnalyticsTrends;
import com.baotin.analytics.application.ModerationStatus;
import com.baotin.analytics.application.Priority;
import com.baotin.analytics.application.QueueCounts;
import com.baotin.analytics.application.QueuePage;
import com.baotin.analytics.application.QueueQuery;
import com.baotin.analytics.application.QueueSort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics/admin")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public AdminSummary summary(
            @AuthenticationPrincipal Jwt jwt,
            @RequestAttribute("correlationId") String correlationId) {
        return service.summary(jwt.getTokenValue(), correlationId);
    }

    @GetMapping("/queue")
    public QueuePage queue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) ModerationStatus status,
            @RequestParam(required = false) Priority priority,
            @RequestParam(defaultValue = "submittedAt,desc") String sort,
            @AuthenticationPrincipal Jwt jwt,
            @RequestAttribute("correlationId") String correlationId) {
        return service.queue(
                new QueueQuery(page, size, status, priority, QueueSort.parse(sort)),
                jwt.getTokenValue(),
                correlationId);
    }

    @GetMapping("/counts")
    public QueueCounts counts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestAttribute("correlationId") String correlationId) {
        return service.counts(jwt.getTokenValue(), correlationId);
    }

    @GetMapping("/trends")
    public AnalyticsTrends trends(@RequestParam(defaultValue = "14") int days) {
        if (days < 1 || days > 90) {
            throw new IllegalArgumentException("days must be between 1 and 90");
        }
        return service.trends(days);
    }
}
