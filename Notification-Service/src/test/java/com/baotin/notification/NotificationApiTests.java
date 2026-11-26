package com.baotin.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baotin.notification.api.PageResponse;
import com.baotin.notification.api.WatchlistResource;
import com.baotin.notification.application.EventIngestor;
import com.baotin.notification.application.NotificationService;
import com.baotin.notification.application.RetentionStore;
import com.baotin.notification.application.WatchlistService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationApiTests {
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        PG.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.flyway.user", PG::getUsername);
        registry.add("spring.flyway.password", PG::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService notifications;
    @Autowired WatchlistService watchlist;
    @Autowired EventIngestor ingestor;
    @Autowired RetentionStore retention;
    @Autowired JsonMapper json;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE notification.delivery_attempts,notification.notifications,notification.watchlist,"
            + "notification.device_tokens,notification.preferences,notification.inbox_events,notification.outbox_events CASCADE");
    }

    @Test
    void livenessDoesNotDependOnDatabaseAndReadinessChecksIt() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unauthenticatedRequestsAreRejectedWithProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void usersOnlyReadAndMutateTheirOwnNotifications() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID bobNotification = notifications.create(bob, "REPORT_STATUS_CHANGED", "Của Bob", "Nội dung", Map.of(), false);

        mvc.perform(authed(get("/api/v1/notifications"), alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(authed(get("/api/v1/notifications/unread-count"), alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(authed(patch("/api/v1/notifications/" + bobNotification + "/read"), alice))
            .andExpect(status().isNotFound());
        mvc.perform(authed(delete("/api/v1/notifications/" + bobNotification), alice))
            .andExpect(status().isNotFound());

        mvc.perform(authed(get("/api/v1/notifications/unread-count"), bob))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void unreadStateReadAllAndUnreadFilterWork() throws Exception {
        UUID user = UUID.randomUUID();
        UUID first = notifications.create(user, "TEST", "Một", "Nội dung", Map.of(), false);
        notifications.create(user, "TEST", "Hai", "Nội dung", Map.of(), false);

        mvc.perform(authed(patch("/api/v1/notifications/" + first + "/read"), user))
            .andExpect(status().isNoContent());
        mvc.perform(authed(get("/api/v1/notifications").param("unread", "true"), user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].read").value(false));
        mvc.perform(authed(post("/api/v1/notifications/read-all"), user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.updated").value(1));
        mvc.perform(authed(get("/api/v1/notifications/unread-count"), user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void preferencesDefaultAndPatchAreScopedToTheCaller() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(authed(get("/api/v1/notifications/preferences"), user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.riskChangeEnabled").value(true))
            .andExpect(jsonPath("$.pushEnabled").value(true));
        mvc.perform(authed(patch("/api/v1/notifications/preferences"), user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pushEnabled\":false,\"inAppEnabled\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pushEnabled").value(false))
            .andExpect(jsonPath("$.inAppEnabled").value(false));
        assertThat(notifications.create(user, "TEST", "Ẩn", "Không kênh nào", Map.of(), true)).isNull();
        mvc.perform(authed(patch("/api/v1/notifications/preferences"), user)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void deviceTokensAreWriteOnlyAndRemovable() throws Exception {
        UUID user = UUID.randomUUID();
        String body = "{\"platform\":\"WEB\",\"token\":\"fcm-secret-token-123\",\"deviceId\":\"browser-1\"}";
        mvc.perform(authed(post("/api/v1/notifications/devices"), user)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.token").doesNotExist());
        mvc.perform(authed(get("/api/v1/notifications/devices"), user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].token").doesNotExist());
        mvc.perform(authed(post("/api/v1/notifications/devices"), user)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        mvc.perform(authed(get("/api/v1/notifications/devices"), user))
            .andExpect(jsonPath("$.items.length()").value(1));

        String id = mvc.perform(authed(get("/api/v1/notifications/devices"), user))
            .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(authed(delete("/api/v1/notifications/devices/" + id), user))
            .andExpect(status().isNoContent());
        mvc.perform(authed(get("/api/v1/notifications/devices"), user))
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void watchlistCanonicalizesAndIsIdempotent() throws Exception {
        UUID user = UUID.randomUUID();
        String first = mvc.perform(authed(post("/api/v1/watchlist"), user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"HTTPS://Example.com:443/a//b?utm_source=x&b=2&a=1#top\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.url").value("https://example.com/a/b?a=1&b=2"))
            .andExpect(jsonPath("$.host").value("example.com"))
            .andReturn().getResponse().getContentAsString();

        mvc.perform(authed(post("/api/v1/watchlist"), user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com/a/b?a=1&b=2\"}"))
            .andExpect(status().isCreated());
        mvc.perform(authed(get("/api/v1/watchlist"), user))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));

        assertThat(notifications.list(user, 0, 20, false).items())
            .filteredOn(item -> item.type().equals("WATCHLIST_ADDED")).hasSize(1);

        String id = json.readTree(first).path("id").asText();
        mvc.perform(authed(delete("/api/v1/watchlist/" + id), user))
            .andExpect(status().isNoContent());
        mvc.perform(authed(get("/api/v1/watchlist"), user))
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void invalidUrlsAndPagingAreRejected() throws Exception {
        UUID user = UUID.randomUUID();
        for (String url : new String[]{"javascript:alert(1)", "https://user:pass@example.com/x", "ftp://example.com/x",
            "https://example.com/reset?token=abc", "https://example.com/callback?access%5Ftoken=abc"}) {
            mvc.perform(authed(post("/api/v1/watchlist"), user)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("url", url))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        mvc.perform(authed(get("/api/v1/notifications").param("size", "101"), user))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_PAGING"));
        mvc.perform(authed(get("/api/v1/watchlist").param("page", "-1"), user))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_PAGING"));
    }

    @Test
    void reputationEventCreatesOneAlertPerSignificantChangeAndIsIdempotent() throws Exception {
        UUID user = UUID.randomUUID();
        WatchlistResource watch = watchlist.add(user, "https://shop-deal-vn.live/checkout");
        assertThat(notifications.list(user, 0, 20, false).items()).hasSize(1);

        UUID eventId = UUID.randomUUID();
        String event = json.writeValueAsString(reputationEvent(eventId, watch.urlTargetId(), watch.url(), 91, "CRITICAL"));
        ingestor.ingest(event);
        PageResponse<com.baotin.notification.api.NotificationResource> after = notifications.list(user, 0, 20, false);
        assertThat(after.items()).hasSize(2);
        assertThat(after.items().getFirst().type()).isEqualTo("WATCHLIST_RISK_CHANGED");
        assertThat(after.items().getFirst().title()).contains("shop-deal-vn.live");
        assertThat(after.items().getFirst().body()).contains("91");

        ingestor.ingest(event);
        assertThat(notifications.list(user, 0, 20, false).items()).hasSize(2);

        ingestor.ingest(json.writeValueAsString(reputationEvent(UUID.randomUUID(), watch.urlTargetId(), watch.url(), 91, "CRITICAL")));
        assertThat(notifications.list(user, 0, 20, false).items()).hasSize(2);

        ingestor.ingest(json.writeValueAsString(reputationEvent(UUID.randomUUID(), watch.urlTargetId(), watch.url(), 40, "MEDIUM")));
        assertThat(notifications.list(user, 0, 20, false).items()).hasSize(3);
    }

    @Test
    void reportEventCreatesANotificationForTheReporter() throws Exception {
        UUID user = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reporterUserId", user.toString());
        payload.put("reportId", UUID.randomUUID().toString());
        payload.put("title", "Shop giả mạo");
        payload.put("status", "VERIFIED");
        payload.put("moderatorNote", "Đã xác minh");
        Map<String, Object> event = envelope("report.verified", payload);
        ingestor.ingest(json.writeValueAsString(event));

        var page = notifications.list(user, 0, 20, false);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().type()).isEqualTo("REPORT_STATUS_CHANGED");
        assertThat(page.items().getFirst().data()).containsEntry("status", "VERIFIED");
    }

    @Test
    void retentionPurgesExpiredDataAndKeepsFailedOrRecentRows() {
        Instant now = Instant.parse("2026-10-27T03:15:00Z");
        UUID user = UUID.randomUUID();
        UUID oldNotification = UUID.randomUUID();
        UUID recentNotification = UUID.randomUUID();

        jdbc.update("INSERT INTO notification.notifications (id,user_id,type,title,body,created_at) "
                + "VALUES (?,?,'TEST','Cũ','Nội dung',?)",
            oldNotification, user, Timestamp.from(now.minus(200, ChronoUnit.DAYS)));
        jdbc.update("INSERT INTO notification.notifications (id,user_id,type,title,body,created_at) "
                + "VALUES (?,?,'TEST','Mới','Nội dung',?)",
            recentNotification, user, Timestamp.from(now.minus(1, ChronoUnit.DAYS)));

        jdbc.update("INSERT INTO notification.delivery_attempts "
                + "(id,notification_id,channel,status,created_at,updated_at) VALUES (?,?,'PUSH','SENT',?,?)",
            UUID.randomUUID(), recentNotification,
            Timestamp.from(now.minus(40, ChronoUnit.DAYS)), Timestamp.from(now.minus(31, ChronoUnit.DAYS)));
        jdbc.update("INSERT INTO notification.delivery_attempts "
                + "(id,notification_id,channel,status,created_at,updated_at) VALUES (?,?,'PUSH','PENDING',?,?)",
            UUID.randomUUID(), recentNotification,
            Timestamp.from(now.minus(40, ChronoUnit.DAYS)), Timestamp.from(now.minus(31, ChronoUnit.DAYS)));

        for (String eventType : new String[]{"OLD_PROCESSED", "NEW_PROCESSED", "OLD_UNPROCESSED"}) {
            boolean processed = !eventType.equals("OLD_UNPROCESSED");
            Timestamp received = Timestamp.from(now.minus(processed ? 31 : 100, ChronoUnit.DAYS));
            Timestamp processedAt = processed
                ? Timestamp.from(now.minus(eventType.equals("OLD_PROCESSED") ? 31 : 1, ChronoUnit.DAYS))
                : null;
            jdbc.update("INSERT INTO notification.inbox_events (event_id,event_type,received_at,processed_at) "
                + "VALUES (?,?,?,?)", UUID.randomUUID(), eventType, received, processedAt);
        }

        insertOutbox(now.minus(31, ChronoUnit.DAYS), "PUBLISHED", now.minus(31, ChronoUnit.DAYS));
        insertOutbox(now.minus(100, ChronoUnit.DAYS), "PENDING", null);
        insertOutbox(now.minus(1, ChronoUnit.DAYS), "PUBLISHED", now.minus(1, ChronoUnit.DAYS));

        jdbc.update("INSERT INTO notification.device_tokens "
                + "(id,user_id,platform,token,device_id,active,last_seen_at) VALUES (?,?,'WEB',?,'old',FALSE,?)",
            UUID.randomUUID(), user, "old-inactive", Timestamp.from(now.minus(200, ChronoUnit.DAYS)));
        jdbc.update("INSERT INTO notification.device_tokens "
                + "(id,user_id,platform,token,device_id,active,last_seen_at) VALUES (?,?,'WEB',?,'new',FALSE,?)",
            UUID.randomUUID(), user, "new-inactive", Timestamp.from(now.minus(1, ChronoUnit.DAYS)));
        jdbc.update("INSERT INTO notification.device_tokens "
                + "(id,user_id,platform,token,device_id,active,last_seen_at) VALUES (?,?,'WEB',?,'active',TRUE,?)",
            UUID.randomUUID(), user, "old-active", Timestamp.from(now.minus(200, ChronoUnit.DAYS)));

        RetentionStore.PurgeResult result = retention.purge(now);

        assertThat(result.notifications()).isEqualTo(1);
        assertThat(result.deliveryAttempts()).isEqualTo(1);
        assertThat(result.inboxEvents()).isEqualTo(1);
        assertThat(result.outboxEvents()).isEqualTo(1);
        assertThat(result.deviceTokens()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.delivery_attempts WHERE status='PENDING'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.inbox_events WHERE processed_at IS NULL",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.outbox_events WHERE status='PENDING'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.device_tokens WHERE active",
            Integer.class)).isEqualTo(1);
    }

    private void insertOutbox(Instant occurredAt, String status, Instant publishedAt) {
        jdbc.update("INSERT INTO notification.outbox_events "
                + "(id,aggregate_type,aggregate_id,event_type,topic,message_key,payload,status,occurred_at,"
                + "published_at,created_at) VALUES (?, 'Test', ?, 'test.event', 'test-topic', 'key', "
                + "'{}'::jsonb, ?, ?, ?, ?)",
            UUID.randomUUID(), UUID.randomUUID(), status, Timestamp.from(occurredAt),
            publishedAt == null ? null : Timestamp.from(publishedAt), Timestamp.from(occurredAt));
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, UUID userId) {
        return request.with(jwt().jwt(builder -> builder.subject(userId.toString())));
    }

    private Map<String, Object> reputationEvent(UUID eventId, UUID targetId, String url, int score, String level) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetId", targetId.toString());
        payload.put("canonicalUrl", url);
        payload.put("oldScore", 20);
        payload.put("newScore", score);
        payload.put("oldLevel", "LOW");
        payload.put("newLevel", level);
        payload.put("reason", "new reports");
        Map<String, Object> event = envelope("reputation.updated", payload);
        event.put("eventId", eventId.toString());
        return event;
    }

    private Map<String, Object> envelope(String type, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", type);
        event.put("eventVersion", 1);
        event.put("occurredAt", "2026-10-22T00:00:00Z");
        event.put("producer", "test");
        event.put("aggregateType", "Test");
        event.put("aggregateId", UUID.randomUUID().toString());
        event.put("correlationId", UUID.randomUUID().toString());
        event.put("causationId", null);
        event.put("payload", payload);
        return event;
    }
}
