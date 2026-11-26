package com.baotin.analytics.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baotin.analytics.application.AdminSummary;
import com.baotin.analytics.application.AnalyticsService;
import com.baotin.analytics.application.AnalyticsTrends;
import com.baotin.analytics.application.DailyTrendPoint;
import com.baotin.analytics.application.ModerationStatus;
import com.baotin.analytics.application.Priority;
import com.baotin.analytics.application.QueueCounts;
import com.baotin.analytics.application.QueuePage;
import com.baotin.analytics.application.QueueQuery;
import com.baotin.analytics.application.QueueSort;
import com.baotin.analytics.application.RiskLevel;
import com.baotin.analytics.application.TrendTotals;
import com.baotin.analytics.integration.UpstreamUnavailableException;
import com.baotin.analytics.security.SecurityConfig;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;

@WebMvcTest(AnalyticsController.class)
@ExtendWith(OutputCaptureExtension.class)
@Import({SecurityConfig.class, ApiProblems.class, CorrelationIdFilter.class})
@TestPropertySource(properties = {
        "analytics.security.jwks-uri=http://localhost:9999/.well-known/jwks.json",
        "analytics.security.issuer=baotin-auth",
        "analytics.security.audience=baotin-api"
})
class AnalyticsControllerTests {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AnalyticsService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void unauthenticatedSummaryReturnsProblem401() throws Exception {
        mvc.perform(get("/api/v1/analytics/admin/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void userRoleIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/analytics/admin/queue")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminAndModeratorCanReadAllThreeEndpoints() throws Exception {
        when(service.summary(any(), any())).thenReturn(new AdminSummary(1, 2, 3, 4, 5));
        when(service.counts(any(), any())).thenReturn(zeroCounts());
        when(service.queue(any(), any(), any())).thenReturn(new QueuePage(List.of(), 0, 20, 0, 0));

        for (String role : List.of("ROLE_ADMIN", "ROLE_MODERATOR")) {
            mvc.perform(get("/api/v1/analytics/admin/summary")
                            .with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/analytics/admin/counts")
                            .with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/analytics/admin/queue")
                            .with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void forwardsTokenAndCorrelationIdToService() throws Exception {
        String correlationId = UUID.randomUUID().toString();
        when(service.summary(any(), any())).thenReturn(new AdminSummary(1, 2, 3, 4, 5));
        when(service.counts(any(), any())).thenReturn(zeroCounts());
        when(service.queue(any(), any(), any())).thenReturn(new QueuePage(List.of(), 1, 10, 0, 0));

        var principal = jwt()
                .jwt(token -> token.tokenValue("forwarded-jwt-token"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        mvc.perform(get("/api/v1/analytics/admin/summary")
                        .header("X-Correlation-Id", correlationId)
                        .with(principal))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/analytics/admin/counts")
                        .header("X-Correlation-Id", correlationId)
                        .with(principal))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/analytics/admin/queue?page=1&size=10&status=SUBMITTED&priority=HIGH&sort=submittedAt,asc")
                        .header("X-Correlation-Id", correlationId)
                        .with(principal))
                .andExpect(status().isOk());

        verify(service).summary("forwarded-jwt-token", correlationId);
        verify(service).counts("forwarded-jwt-token", correlationId);
        verify(service).queue(
                new QueueQuery(1, 10, ModerationStatus.SUBMITTED, Priority.HIGH, QueueSort.parse("submittedAt,asc")),
                "forwarded-jwt-token",
                correlationId);
    }

    @Test
    void unverifiableTokenReturnsProblem401() throws Exception {
        when(jwtDecoder.decode("bad-token")).thenThrow(new BadJwtException("no matching key"));

        mvc.perform(get("/api/v1/analytics/admin/summary")
                        .header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void storeFailureReturnsGenericProblem500WithoutDetails() throws Exception {
        when(service.summary(any(), any())).thenThrow(new DataAccessResourceFailureException("db down at secret-host"));

        mvc.perform(get("/api/v1/analytics/admin/summary")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail", not(containsString("db down"))));
    }

    @Test
    void upstreamFailureReturnsProblem503(CapturedOutput output) throws Exception {
        String correlationId = UUID.randomUUID().toString();
        when(service.summary(any(), any())).thenThrow(new UpstreamUnavailableException("report"));
        when(service.queue(any(), any(), any())).thenThrow(new UpstreamUnavailableException("report"));
        when(service.counts(any(), any())).thenThrow(new UpstreamUnavailableException("report"));

        var principal = jwt()
                .jwt(token -> token.tokenValue("secret-jwt-token"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        for (String path : List.of(
                "/api/v1/analytics/admin/summary",
                "/api/v1/analytics/admin/queue",
                "/api/v1/analytics/admin/counts")) {
            mvc.perform(get(path)
                            .header("X-Correlation-Id", correlationId)
                            .with(principal))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("ANALYTICS_UPSTREAM_UNAVAILABLE"))
                    .andExpect(jsonPath("$.detail").value("Analytics data source unavailable"))
                    .andExpect(jsonPath("$.correlationId").value(correlationId));
        }

        String logs = output.toString();
        assertTrue(logs.contains("WARN"));
        assertTrue(logs.contains("report"));
        assertTrue(logs.contains(correlationId));
        assertFalse(logs.contains("secret-jwt-token"));
    }

    @Test
    void invalidQueueInputReturnsProblem400AndNeverCallsService() throws Exception {
        for (String query : List.of(
                "page=-1",
                "page=10001",
                "size=0",
                "size=101",
                "status=NOPE",
                "priority=NOPE",
                "sort=submittedAt",
                "sort=unknown,desc",
                "sort=submittedAt,sideways",
                "sort=submittedAt,desc,extra")) {
            mvc.perform(get("/api/v1/analytics/admin/queue?" + query)
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void correlationIdIsEchoedWhenValidAndReplacedWhenInvalid() throws Exception {
        String valid = UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/analytics/admin/summary")
                        .header("X-Correlation-Id", valid)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(header().string("X-Correlation-Id", valid));
        mvc.perform(get("/api/v1/analytics/admin/summary")
                        .header("X-Correlation-Id", "not-a-uuid")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(header().string("X-Correlation-Id", not("not-a-uuid")));
    }

    @Test
    void trendsEnforcesAuthRolesAndSerializesExactContractFields() throws Exception {
        mvc.perform(get("/api/v1/analytics/admin/trends"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(get("/api/v1/analytics/admin/trends")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        AnalyticsTrends sampleTrends = new AnalyticsTrends(
                14,
                LocalDate.of(2026, 11, 3),
                LocalDate.of(2026, 11, 16),
                new TrendTotals(420L, 250L, 170L, 85L, 42L, 29L, 8L, 5L, 19L),
                Map.of(
                        RiskLevel.LOW, 210L,
                        RiskLevel.MEDIUM, 125L,
                        RiskLevel.HIGH, 60L,
                        RiskLevel.CRITICAL, 25L),
                List.of(new DailyTrendPoint(
                        LocalDate.of(2026, 11, 3), 30L, 18L, 12L, 6L, 3L, 2L, 1L, 0L, 1L)));
        when(service.trends(14)).thenReturn(sampleTrends);
        when(service.trends(7)).thenReturn(sampleTrends);

        String correlationId = UUID.randomUUID().toString();
        for (String role : List.of("ROLE_ADMIN", "ROLE_MODERATOR")) {
            String body = mvc.perform(get("/api/v1/analytics/admin/trends")
                            .header("X-Correlation-Id", correlationId)
                            .with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Correlation-Id", correlationId))
                    .andExpect(jsonPath("$.days").value(14))
                    .andExpect(jsonPath("$.fromDate").value("2026-11-03"))
                    .andExpect(jsonPath("$.toDate").value("2026-11-16"))
                    .andExpect(jsonPath("$.totals.totalScans").value(420))
                    .andExpect(jsonPath("$.totals.textScans").value(250))
                    .andExpect(jsonPath("$.totals.urlScans").value(170))
                    .andExpect(jsonPath("$.totals.highRiskScans").value(85))
                    .andExpect(jsonPath("$.totals.reportsCreated").value(42))
                    .andExpect(jsonPath("$.totals.reportsVerified").value(29))
                    .andExpect(jsonPath("$.totals.reportsRejected").value(8))
                    .andExpect(jsonPath("$.totals.reportsNeedMoreInfo").value(5))
                    .andExpect(jsonPath("$.totals.newThreats").value(19))
                    .andExpect(jsonPath("$.riskBreakdown.LOW").value(210))
                    .andExpect(jsonPath("$.riskBreakdown.MEDIUM").value(125))
                    .andExpect(jsonPath("$.riskBreakdown.HIGH").value(60))
                    .andExpect(jsonPath("$.riskBreakdown.CRITICAL").value(25))
                    .andExpect(jsonPath("$.series[0].date").value("2026-11-03"))
                    .andExpect(jsonPath("$.series[0].totalScans").value(30))
                    .andExpect(jsonPath("$.series[0].textScans").value(18))
                    .andExpect(jsonPath("$.series[0].urlScans").value(12))
                    .andExpect(jsonPath("$.series[0].highRiskScans").value(6))
                    .andExpect(jsonPath("$.series[0].reportsCreated").value(3))
                    .andExpect(jsonPath("$.series[0].reportsVerified").value(2))
                    .andExpect(jsonPath("$.series[0].reportsRejected").value(1))
                    .andExpect(jsonPath("$.series[0].reportsNeedMoreInfo").value(0))
                    .andExpect(jsonPath("$.series[0].newThreats").value(1))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertFalse(body.contains("smsScans"));
            assertFalse(body.contains("activeUsers"));
        }

        mvc.perform(get("/api/v1/analytics/admin/trends?days=7")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
        verify(service).trends(7);
    }

    @Test
    void invalidTrendsDaysReturnsProblem400AndNeverCallsService() throws Exception {
        for (String query : List.of("days=0", "days=-1", "days=91", "days=abc")) {
            mvc.perform(get("/api/v1/analytics/admin/trends?" + query)
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void trendsStoreFailureReturnsGenericProblem500WithoutDetails() throws Exception {
        when(service.trends(14)).thenThrow(new DataAccessResourceFailureException("postgres down at secret-host"));

        mvc.perform(get("/api/v1/analytics/admin/trends")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail", not(containsString("postgres down"))));
    }

    private static QueueCounts zeroCounts() {
        var byStatus = new EnumMap<ModerationStatus, Long>(ModerationStatus.class);
        for (var status : ModerationStatus.values()) {
            byStatus.put(status, 0L);
        }
        var byPriority = new EnumMap<Priority, Long>(Priority.class);
        for (var priority : Priority.values()) {
            byPriority.put(priority, 0L);
        }
        return new QueueCounts(byStatus, byPriority);
    }
}
