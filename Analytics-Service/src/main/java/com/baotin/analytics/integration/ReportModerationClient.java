package com.baotin.analytics.integration;

import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

@Component
public class ReportModerationClient {

    private static final String UPSTREAM_NAME = "report";

    private final RestClient restClient;

    public ReportModerationClient(@Qualifier("reportRestClient") RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    }

    public ReportModerationView moderation(
            String status,
            String priority,
            int page,
            int size,
            String sort,
            String accessToken,
            String correlationId) {
        ReportModerationView view = UpstreamClientConfig.getJson(
                restClient,
                UPSTREAM_NAME,
                uriBuilder -> {
                    UriBuilder builder = uriBuilder
                            .path("/internal/v1/analytics/moderation")
                            .queryParam("page", page)
                            .queryParam("size", size)
                            .queryParam("sort", sort);
                    if (status != null) {
                        builder.queryParam("status", status);
                    }
                    if (priority != null) {
                        builder.queryParam("priority", priority);
                    }
                    return builder.build();
                },
                accessToken,
                correlationId,
                ReportModerationView.class);
        if (!isValid(view)) {
            throw new UpstreamUnavailableException(UPSTREAM_NAME);
        }
        return view;
    }

    private static boolean isValid(ReportModerationView view) {
        if (view.items() == null || view.counts() == null
                || view.page() < 0 || view.size() < 1
                || view.totalItems() < 0 || view.totalPages() < 0) {
            return false;
        }
        ReportModerationView.Counts counts = view.counts();
        if (counts.byStatus() == null || counts.byPriority() == null || counts.resolvedToday() < 0
                || !hasNonNegativeValues(counts.byStatus())
                || !hasNonNegativeValues(counts.byPriority())) {
            return false;
        }
        for (ReportModerationView.Item item : view.items()) {
            if (!isValidItem(item)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidItem(ReportModerationView.Item item) {
        return item != null
                && item.id() != null
                && isPresent(item.targetType())
                && isPresent(item.target())
                && isPresent(item.category())
                && isPresent(item.title())
                && isPresent(item.status())
                && isPresent(item.priority())
                && item.submittedAt() != null
                && item.evidenceCount() >= 0;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasNonNegativeValues(Map<String, Long> counts) {
        return counts.values().stream().allMatch(value -> value != null && value >= 0);
    }
}
