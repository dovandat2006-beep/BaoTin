package com.baotin.analytics.integration;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ThreatStatsClient {

    private static final String UPSTREAM_NAME = "threat";

    private final RestClient restClient;

    public ThreatStatsClient(@Qualifier("threatRestClient") RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    }

    public ThreatScanStats stats(String accessToken, String correlationId) {
        ThreatScanStats stats = UpstreamClientConfig.getJson(
                restClient,
                UPSTREAM_NAME,
                uriBuilder -> uriBuilder.path("/api/v1/scans/admin/stats").build(),
                accessToken,
                correlationId,
                ThreatScanStats.class);
        if (stats.scansToday() < 0 || stats.highRiskScansToday() < 0) {
            throw new UpstreamUnavailableException(UPSTREAM_NAME);
        }
        return stats;
    }
}
