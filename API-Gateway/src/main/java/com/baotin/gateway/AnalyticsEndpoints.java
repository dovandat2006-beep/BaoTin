package com.baotin.gateway;

import java.util.List;
import org.springframework.http.HttpMethod;

/** Reviewed Analytics-Service allowlist. Admin reads only; no wildcard or anonymous route. */
final class AnalyticsEndpoints {
    private AnalyticsEndpoints() {}
    static List<ServiceCatalog.Endpoint> all() {
        return List.of(
            endpoint("analytics-summary","/api/v1/analytics/admin/summary"),
            endpoint("analytics-queue","/api/v1/analytics/admin/queue"),
            endpoint("analytics-counts","/api/v1/analytics/admin/counts"),
            endpoint("analytics-trends","/api/v1/analytics/admin/trends"));
    }
    private static ServiceCatalog.Endpoint endpoint(String id,String path) {
        return new ServiceCatalog.Endpoint(id,"analytics",path,List.of(HttpMethod.GET),false);
    }
}
