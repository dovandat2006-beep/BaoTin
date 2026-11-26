package com.baotin.analytics.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class UpstreamClientsTests {

    @Test
    void reportClientSendsExactRequestAndMapsResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://report.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReportModerationClient client = new ReportModerationClient(builder.build());
        String correlation = UUID.randomUUID().toString();

        server.expect(requestTo(
                        "http://report.test/internal/v1/analytics/moderation?page=2&size=50&sort=submittedAt,asc&status=SUBMITTED&priority=HIGH"))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andExpect(header("X-Correlation-Id", correlation))
                .andRespond(withSuccess("""
                        {"items":[{"id":"11111111-1111-4111-8111-111111111111","targetType":"URL",
                        "target":"https://example.test/","category":"PHISHING_URL","title":"Title",
                        "status":"SUBMITTED","priority":"HIGH","submittedAt":"2026-11-16T09:00:00Z",
                        "evidenceCount":2,"moderatorNote":null}],
                        "page":2,"size":50,"totalItems":1,"totalPages":1,
                        "counts":{"byStatus":{"SUBMITTED":1},"byPriority":{"HIGH":1},"resolvedToday":0}}
                        """, MediaType.APPLICATION_JSON));

        ReportModerationView view = client.moderation(
                "SUBMITTED", "HIGH", 2, 50, "submittedAt,asc", "access-token", correlation);

        assertEquals(2, view.page());
        assertEquals(50, view.size());
        assertEquals(1, view.totalItems());
        assertEquals(1, view.totalPages());
        assertEquals(1, view.items().size());
        ReportModerationView.Item item = view.items().getFirst();
        assertEquals(UUID.fromString("11111111-1111-4111-8111-111111111111"), item.id());
        assertEquals("URL", item.targetType());
        assertEquals("https://example.test/", item.target());
        assertEquals("PHISHING_URL", item.category());
        assertEquals("Title", item.title());
        assertEquals("SUBMITTED", item.status());
        assertEquals("HIGH", item.priority());
        assertEquals(Instant.parse("2026-11-16T09:00:00Z"), item.submittedAt());
        assertEquals(2, item.evidenceCount());
        assertNull(item.moderatorNote());
        assertEquals(1L, view.counts().byStatus().get("SUBMITTED"));
        assertEquals(1L, view.counts().byPriority().get("HIGH"));
        assertEquals(0L, view.counts().resolvedToday());
        server.verify();
    }

    @Test
    void reportClientOmitsNullStatusAndPriorityQueryParams() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://report.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReportModerationClient client = new ReportModerationClient(builder.build());
        String correlation = UUID.randomUUID().toString();

        server.expect(requestTo(
                        "http://report.test/internal/v1/analytics/moderation?page=0&size=1&sort=submittedAt,desc"))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andExpect(header("X-Correlation-Id", correlation))
                .andRespond(withSuccess("""
                        {"items":[],"page":0,"size":1,"totalItems":0,"totalPages":0,
                        "counts":{"byStatus":{},"byPriority":{},"resolvedToday":4}}
                        """, MediaType.APPLICATION_JSON));

        ReportModerationView view = client.moderation(
                null, null, 0, 1, "submittedAt,desc", "access-token", correlation);

        assertEquals(0, view.items().size());
        assertEquals(4L, view.counts().resolvedToday());
        server.verify();
    }

    @Test
    void threatClientMapsStatsAndForwardsBothHeaders() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://threat.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ThreatStatsClient client = new ThreatStatsClient(builder.build());
        String correlation = UUID.randomUUID().toString();

        server.expect(requestTo("http://threat.test/api/v1/scans/admin/stats"))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andExpect(header("X-Correlation-Id", correlation))
                .andRespond(withSuccess("""
                        {"scansToday":42,"highRiskScansToday":7}
                        """, MediaType.APPLICATION_JSON));

        ThreatScanStats stats = client.stats("access-token", correlation);

        assertEquals(42L, stats.scansToday());
        assertEquals(7L, stats.highRiskScansToday());
        server.verify();
    }

    @Test
    void serviceUnavailable503ThrowsUpstreamUnavailableWithoutLeakingBodyOrToken() {
        RestClient.Builder reportBuilder = RestClient.builder().baseUrl("http://report.test");
        MockRestServiceServer reportServer = MockRestServiceServer.bindTo(reportBuilder).build();
        ReportModerationClient reportClient = new ReportModerationClient(reportBuilder.build());
        reportServer.expect(requestTo("http://report.test/internal/v1/analytics/moderation?page=0&size=1&sort=submittedAt,desc"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"internal-db-password\"}"));

        UpstreamUnavailableException reportEx = assertThrows(UpstreamUnavailableException.class, () ->
                reportClient.moderation(null, null, 0, 1, "submittedAt,desc", "secret-jwt-token", "corr-1"));
        assertEquals("report", reportEx.upstream());
        assertNoSecretLeak(reportEx, "internal-db-password", "secret-jwt-token");
        reportServer.verify();

        RestClient.Builder threatBuilder = RestClient.builder().baseUrl("http://threat.test");
        MockRestServiceServer threatServer = MockRestServiceServer.bindTo(threatBuilder).build();
        ThreatStatsClient threatClient = new ThreatStatsClient(threatBuilder.build());
        threatServer.expect(requestTo("http://threat.test/api/v1/scans/admin/stats"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"secret\":\"threat-internal-trace\"}"));

        UpstreamUnavailableException threatEx = assertThrows(UpstreamUnavailableException.class, () ->
                threatClient.stats("secret-jwt-token", "corr-2"));
        assertEquals("threat", threatEx.upstream());
        assertNoSecretLeak(threatEx, "threat-internal-trace", "secret-jwt-token");
        threatServer.verify();
    }

    @Test
    void unauthorized401AndForbidden403ThrowUpstreamUnavailableWithoutLeakingBodyOrToken() {
        RestClient.Builder reportBuilder = RestClient.builder().baseUrl("http://report.test");
        MockRestServiceServer reportServer = MockRestServiceServer.bindTo(reportBuilder).build();
        ReportModerationClient reportClient = new ReportModerationClient(reportBuilder.build());
        reportServer.expect(requestTo("http://report.test/internal/v1/analytics/moderation?page=0&size=1&sort=submittedAt,desc"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"upstream-auth-body\"}"));

        UpstreamUnavailableException reportEx = assertThrows(UpstreamUnavailableException.class, () ->
                reportClient.moderation(null, null, 0, 1, "submittedAt,desc", "secret-jwt-token", "corr-1"));
        assertEquals("report", reportEx.upstream());
        assertNoSecretLeak(reportEx, "upstream-auth-body", "secret-jwt-token");
        reportServer.verify();

        RestClient.Builder threatBuilder = RestClient.builder().baseUrl("http://threat.test");
        MockRestServiceServer threatServer = MockRestServiceServer.bindTo(threatBuilder).build();
        ThreatStatsClient threatClient = new ThreatStatsClient(threatBuilder.build());
        threatServer.expect(requestTo("http://threat.test/api/v1/scans/admin/stats"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"forbidden-detail-body\"}"));

        UpstreamUnavailableException threatEx = assertThrows(UpstreamUnavailableException.class, () ->
                threatClient.stats("secret-jwt-token", "corr-2"));
        assertEquals("threat", threatEx.upstream());
        assertNoSecretLeak(threatEx, "forbidden-detail-body", "secret-jwt-token");
        threatServer.verify();
    }

    @Test
    void empty200BodyThrowsUpstreamUnavailable() {
        RestClient.Builder reportBuilder = RestClient.builder().baseUrl("http://report.test");
        MockRestServiceServer reportServer = MockRestServiceServer.bindTo(reportBuilder).build();
        ReportModerationClient reportClient = new ReportModerationClient(reportBuilder.build());
        reportServer.expect(requestTo("http://report.test/internal/v1/analytics/moderation?page=0&size=1&sort=submittedAt,desc"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        UpstreamUnavailableException reportEx = assertThrows(UpstreamUnavailableException.class, () ->
                reportClient.moderation(null, null, 0, 1, "submittedAt,desc", "access-token", "corr-1"));
        assertEquals("report", reportEx.upstream());
        reportServer.verify();

        RestClient.Builder threatBuilder = RestClient.builder().baseUrl("http://threat.test");
        MockRestServiceServer threatServer = MockRestServiceServer.bindTo(threatBuilder).build();
        ThreatStatsClient threatClient = new ThreatStatsClient(threatBuilder.build());
        threatServer.expect(requestTo("http://threat.test/api/v1/scans/admin/stats"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        UpstreamUnavailableException threatEx = assertThrows(UpstreamUnavailableException.class, () ->
                threatClient.stats("access-token", "corr-2"));
        assertEquals("threat", threatEx.upstream());
        threatServer.verify();
    }

    @Test
    void malformedJson200BodyThrowsUpstreamUnavailableWithoutLeakingBodyOrToken() {
        RestClient.Builder reportBuilder = RestClient.builder().baseUrl("http://report.test");
        MockRestServiceServer reportServer = MockRestServiceServer.bindTo(reportBuilder).build();
        ReportModerationClient reportClient = new ReportModerationClient(reportBuilder.build());
        reportServer.expect(requestTo("http://report.test/internal/v1/analytics/moderation?page=0&size=1&sort=submittedAt,desc"))
                .andRespond(withSuccess("{malformed-report-payload", MediaType.APPLICATION_JSON));

        UpstreamUnavailableException reportEx = assertThrows(UpstreamUnavailableException.class, () ->
                reportClient.moderation(null, null, 0, 1, "submittedAt,desc", "secret-jwt-token", "corr-1"));
        assertEquals("report", reportEx.upstream());
        assertNoSecretLeak(reportEx, "malformed-report-payload", "secret-jwt-token");
        reportServer.verify();

        RestClient.Builder threatBuilder = RestClient.builder().baseUrl("http://threat.test");
        MockRestServiceServer threatServer = MockRestServiceServer.bindTo(threatBuilder).build();
        ThreatStatsClient threatClient = new ThreatStatsClient(threatBuilder.build());
        threatServer.expect(requestTo("http://threat.test/api/v1/scans/admin/stats"))
                .andRespond(withSuccess("{malformed-threat-payload", MediaType.APPLICATION_JSON));

        UpstreamUnavailableException threatEx = assertThrows(UpstreamUnavailableException.class, () ->
                threatClient.stats("secret-jwt-token", "corr-2"));
        assertEquals("threat", threatEx.upstream());
        assertNoSecretLeak(threatEx, "malformed-threat-payload", "secret-jwt-token");
        threatServer.verify();
    }

    @Test
    void connectionOrTimeoutFailureThrowsUpstreamUnavailableWithoutLeakingToken() {
        ClientHttpRequestFactory failingFactory = (uri, httpMethod) -> {
            throw new SocketTimeoutException("read timed out to secret-internal-host:8183");
        };
        RestClient failingReportRestClient = RestClient.builder()
                .baseUrl("http://report.test")
                .requestFactory(failingFactory)
                .build();
        ReportModerationClient reportClient = new ReportModerationClient(failingReportRestClient);

        UpstreamUnavailableException reportEx = assertThrows(UpstreamUnavailableException.class, () ->
                reportClient.moderation(null, null, 0, 1, "submittedAt,desc", "secret-jwt-token", "corr-1"));
        assertEquals("report", reportEx.upstream());
        assertNoSecretLeak(reportEx, "secret-internal-host", "secret-jwt-token");

        ClientHttpRequestFactory ioFailingFactory = (uri, httpMethod) -> {
            throw new IOException("connection refused to threat-host:8182");
        };
        RestClient failingThreatRestClient = RestClient.builder()
                .baseUrl("http://threat.test")
                .requestFactory(ioFailingFactory)
                .build();
        ThreatStatsClient threatClient = new ThreatStatsClient(failingThreatRestClient);

        UpstreamUnavailableException threatEx = assertThrows(UpstreamUnavailableException.class, () ->
                threatClient.stats("secret-jwt-token", "corr-2"));
        assertEquals("threat", threatEx.upstream());
        assertNoSecretLeak(threatEx, "threat-host", "secret-jwt-token");
    }

    @Test
    void incompleteOrInvalidReport200JsonPayloadsThrowUpstreamUnavailable() {
        List<String> invalidPayloads = List.of(
                "{}",
                "{\"items\":[],\"page\":0,\"size\":1,\"totalItems\":0,\"totalPages\":0}",
                "{\"page\":0,\"size\":1,\"totalItems\":0,\"totalPages\":0,\"counts\":{\"resolvedToday\":0}}",
                "{\"items\":[],\"page\":0,\"size\":1,\"totalItems\":0,\"totalPages\":0,\"counts\":{\"byStatus\":{},\"resolvedToday\":0}}",
                "{\"items\":[],\"page\":0,\"size\":1,\"totalItems\":0,\"totalPages\":0,\"counts\":{\"byPriority\":{},\"resolvedToday\":0}}",
                "{\"items\":[],\"page\":-1,\"size\":1,\"totalItems\":0,\"totalPages\":0,\"counts\":{\"byStatus\":{},\"byPriority\":{},\"resolvedToday\":0}}",
                "{\"items\":[],\"page\":0,\"size\":0,\"totalItems\":0,\"totalPages\":0,\"counts\":{\"byStatus\":{},\"byPriority\":{},\"resolvedToday\":0}}",
                "{\"items\":[],\"page\":0,\"size\":1,\"totalItems\":-1,\"totalPages\":0,\"counts\":{\"byStatus\":{},\"byPriority\":{},\"resolvedToday\":0}}",
                "{\"items\":[],\"page\":0,\"size\":1,\"totalItems\":0,\"totalPages\":-1,\"counts\":{\"byStatus\":{},\"byPriority\":{},\"resolvedToday\":0}}",
                "{\"items\":[],\"page\":0,\"size\":1,\"totalItems\":0,\"totalPages\":0,\"counts\":{\"byStatus\":{\"SUBMITTED\":-1},\"byPriority\":{},\"resolvedToday\":0}}",
                "{\"items\":[],\"page\":0,\"size\":1,\"totalItems\":0,\"totalPages\":0,\"counts\":{\"byStatus\":{},\"byPriority\":{},\"resolvedToday\":-1}}",
                """
                {"items":[{"id":"11111111-1111-4111-8111-111111111111","targetType":"URL",
                "target":"https://example.test/","category":"PHISHING_URL","title":"Title",
                "priority":"HIGH","submittedAt":"2026-11-16T09:00:00Z","evidenceCount":2,"moderatorNote":null}],
                "page":0,"size":1,"totalItems":1,"totalPages":1,
                "counts":{"byStatus":{},"byPriority":{},"resolvedToday":0}}
                """,
                """
                {"items":[{"id":"11111111-1111-4111-8111-111111111111","targetType":"URL",
                "target":"https://example.test/","category":"PHISHING_URL","title":"Title",
                "status":"SUBMITTED","priority":"HIGH","evidenceCount":2,"moderatorNote":null}],
                "page":0,"size":1,"totalItems":1,"totalPages":1,
                "counts":{"byStatus":{},"byPriority":{},"resolvedToday":0}}
                """,
                """
                {"items":[{"id":"11111111-1111-4111-8111-111111111111","targetType":"URL",
                "target":"https://example.test/","category":"PHISHING_URL","title":"Title",
                "status":"SUBMITTED","priority":"HIGH","submittedAt":"2026-11-16T09:00:00Z",
                "evidenceCount":-1,"moderatorNote":null}],
                "page":0,"size":1,"totalItems":1,"totalPages":1,
                "counts":{"byStatus":{},"byPriority":{},"resolvedToday":0}}
                """);

        for (String payload : invalidPayloads) {
            RestClient.Builder builder = RestClient.builder().baseUrl("http://report.test");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            ReportModerationClient client = new ReportModerationClient(builder.build());
            server.expect(requestTo("http://report.test/internal/v1/analytics/moderation?page=0&size=1&sort=submittedAt,desc"))
                    .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));

            UpstreamUnavailableException ex = assertThrows(
                    UpstreamUnavailableException.class,
                    () -> client.moderation(null, null, 0, 1, "submittedAt,desc", "access-token", "corr-1"),
                    "Expected UpstreamUnavailableException for payload: " + payload);
            assertEquals("report", ex.upstream());
            server.verify();
        }
    }

    @Test
    void incompleteOrInvalidThreat200JsonPayloadsThrowUpstreamUnavailable() {
        List<String> invalidPayloads = List.of(
                "{}",
                "{\"scansToday\":42}",
                "{\"highRiskScansToday\":7}",
                "{\"scansToday\":-1,\"highRiskScansToday\":0}",
                "{\"scansToday\":10,\"highRiskScansToday\":-1}");

        for (String payload : invalidPayloads) {
            RestClient.Builder builder = RestClient.builder().baseUrl("http://threat.test");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            ThreatStatsClient client = new ThreatStatsClient(builder.build());
            server.expect(requestTo("http://threat.test/api/v1/scans/admin/stats"))
                    .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));

            UpstreamUnavailableException ex = assertThrows(
                    UpstreamUnavailableException.class,
                    () -> client.stats("access-token", "corr-2"),
                    "Expected UpstreamUnavailableException for payload: " + payload);
            assertEquals("threat", ex.upstream());
            server.verify();
        }
    }

    @Test
    void upstreamClientConfigValidatesInputsAndConfiguresTimeouts() {
        UpstreamClientConfig config = new UpstreamClientConfig();

        RestClient reportClient = config.reportRestClient("http://localhost:8183", 3000);
        RestClient threatClient = config.threatRestClient("http://localhost:8182", 2500);
        assertConfiguredTimeouts(reportClient, 3000);
        assertConfiguredTimeouts(threatClient, 2500);

        assertThrows(IllegalArgumentException.class, () ->
                config.reportRestClient("http://localhost:8183", 0));
        assertThrows(IllegalArgumentException.class, () ->
                config.threatRestClient("http://localhost:8182", -100));
        assertThrows(IllegalArgumentException.class, () ->
                config.reportRestClient(" ", 3000));
    }

    @Test
    void springContextWiresQualifiedRestClientsWithConfiguredTimeouts() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("analytics.report-uri", "http://report.internal:8183")
                .withProperty("analytics.threat-uri", "http://threat.internal:8182")
                .withProperty("analytics.upstream-timeout-ms", "1500");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(env);
            context.register(UpstreamClientConfig.class, ReportModerationClient.class, ThreatStatsClient.class);
            context.refresh();

            RestClient reportRestClient = context.getBean("reportRestClient", RestClient.class);
            RestClient threatRestClient = context.getBean("threatRestClient", RestClient.class);
            ReportModerationClient reportClient = context.getBean(ReportModerationClient.class);
            ThreatStatsClient threatClient = context.getBean(ThreatStatsClient.class);

            assertSame(reportRestClient, ReflectionTestUtils.getField(reportClient, "restClient"));
            assertSame(threatRestClient, ReflectionTestUtils.getField(threatClient, "restClient"));
            assertConfiguredTimeouts(reportRestClient, 1500);
            assertConfiguredTimeouts(threatRestClient, 1500);
        }
    }

    private static void assertConfiguredTimeouts(RestClient restClient, int expectedTimeoutMs) {
        assertNotNull(restClient);
        ClientHttpRequestFactory factory = assertInstanceOf(
                ClientHttpRequestFactory.class,
                ReflectionTestUtils.getField(restClient, "clientRequestFactory"));
        SimpleClientHttpRequestFactory simpleFactory = assertInstanceOf(
                SimpleClientHttpRequestFactory.class,
                factory);
        assertEquals(expectedTimeoutMs, ReflectionTestUtils.getField(simpleFactory, "connectTimeout"));
        assertEquals(expectedTimeoutMs, ReflectionTestUtils.getField(simpleFactory, "readTimeout"));
    }

    private static void assertNoSecretLeak(Throwable throwable, String bodyFragment, String token) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null) {
                assertFalse(message.contains(bodyFragment), "Exception message leaked upstream details: " + message);
                assertFalse(message.contains(token), "Exception message leaked access token");
            }
        }
    }
}
