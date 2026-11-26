package com.baotin.gateway;

import java.util.*;
import org.springframework.http.HttpMethod;

/** Reviewed Report-Service allowlist. Internal endpoints are never routable. */
final class ReportEndpoints {
    private ReportEndpoints() {}
    static List<ServiceCatalog.Endpoint> all() {
        return List.of(
            endpoint("report-create","/api/v1/reports",HttpMethod.POST),
            endpoint("report-mine","/api/v1/reports/me",HttpMethod.GET),
            endpoint("report-detail","/api/v1/reports/{id}",HttpMethod.GET),
            endpoint("report-evidence-upload","/api/v1/reports/{id}/evidence",HttpMethod.POST),
            endpoint("report-evidence","/api/v1/reports/{id}/evidence/{evidenceId}",HttpMethod.GET,HttpMethod.DELETE),
            endpoint("report-resubmit","/api/v1/reports/{id}/resubmit",HttpMethod.POST),
            endpoint("moderation-queue","/api/v1/moderation/reports",HttpMethod.GET),
            endpoint("moderation-detail","/api/v1/moderation/reports/{id}",HttpMethod.GET),
            endpoint("moderation-evidence","/api/v1/moderation/reports/{id}/evidence/{evidenceId}",HttpMethod.GET),
            endpoint("moderation-start","/api/v1/moderation/reports/{id}/start-review",HttpMethod.POST),
            endpoint("moderation-verify","/api/v1/moderation/reports/{id}/verify",HttpMethod.POST),
            endpoint("moderation-reject","/api/v1/moderation/reports/{id}/reject",HttpMethod.POST),
            endpoint("moderation-info","/api/v1/moderation/reports/{id}/request-info",HttpMethod.POST));
    }
    private static ServiceCatalog.Endpoint endpoint(String id,String path,HttpMethod... methods) {
        return new ServiceCatalog.Endpoint(id,"report",path,List.of(methods),false);
    }
}
