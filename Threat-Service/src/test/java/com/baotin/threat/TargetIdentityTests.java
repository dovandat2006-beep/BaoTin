package com.baotin.threat;

import com.baotin.threat.domain.TargetIdentity;
import com.baotin.threat.application.TargetService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TargetIdentityTests {
    static final org.testcontainers.containers.PostgreSQLContainer<?> DB = new org.testcontainers.containers.PostgreSQLContainer<>("postgres:17.6-alpine");
    static final java.util.UUID LEGACY = java.util.UUID.randomUUID();
    static {
        DB.start();
        org.flywaydb.core.Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())
            .defaultSchema("threat").schemas("threat").target("1").load().migrate();
        try (var connection=java.sql.DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
             var statement=connection.prepareStatement("INSERT INTO threat.targets(id,type,canonical_hash,normalized_url,created_at) VALUES(?,'URL',?, ?,CURRENT_TIMESTAMP)")) {
            statement.setObject(1,LEGACY);
            statement.setString(2,TargetIdentity.of("URL","https://legacy.example/").hash());
            statement.setString(3,"https://legacy.example/"); statement.executeUpdate();
        } catch(java.sql.SQLException error) { throw new ExceptionInInitializerError(error); }
    }
    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("spring.flyway.user", DB::getUsername);
        r.add("spring.flyway.password", DB::getPassword);
        r.add("scan.hash-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
        r.add("threat.notification-service-token", () -> "notification-private-target-credential-at-least-32");
    }
    @org.junit.jupiter.api.AfterAll static void shutdown() { DB.stop(); }

    @Autowired TargetService service;
    @Autowired MockMvc mvc;
    @Test void scanMigrationPreservesPreviouslyResolvedTargetIds() {
        assertEquals(LEGACY,service.resolve("URL","https://legacy.example/").id());
    }
    @Test void urlCanonicalizationStripsSensitiveQueryAndFragment() {
        var a=service.resolve("URL","HTTPS://Example.test:443/a/../?utm_source=mail&token=secret&b=2&a=1#private");
        var b=service.resolve("URL","https://example.test/?b=2&a=1");
        assertEquals(a.id(),b.id());assertEquals("https://example.test/?b=2&a=1",a.displayValue());
    }
    @Test void messageFingerprintDoesNotPersistOrExposeRawText() {
        var a=service.resolve("MESSAGE_TEMPLATE","  GỬI   MÃ OTP  ");var b=service.resolve("MESSAGE_TEMPLATE","gửi mã otp");
        assertEquals(a.id(),b.id());assertFalse(a.displayValue().contains("otp"));
    }
    @Test void unsafeUrlsAreRejected() {
        for(String input:java.util.List.of("file:///etc/passwd","ftp://example.test/a","https://user:secret@example.test","not a url")) assertThrows(IllegalArgumentException.class,()->TargetIdentity.of("URL",input));
    }
    @Test void internalEndpointRequiresCredentialAndIsNotPublic() throws Exception {
        String body="{\"type\":\"URL\",\"value\":\"https://example.test/\"}";
        mvc.perform(post("/internal/v1/targets/resolve").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/targets/resolve").header("X-Service-Token","wrong").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/targets/resolve").header("X-Service-Token","test-internal-service-token-at-least-32-characters").contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/targets")).andExpect(status().isUnauthorized());
    }
    @Test void notificationUsesItsOwnPrivateTargetCredential() throws Exception {
        mvc.perform(post("/internal/v1/targets/resolve").header("X-Service-Token","notification-private-target-credential-at-least-32")
            .contentType("application/json").content("{\"type\":\"URL\",\"value\":\"https://watch.example/\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/internal/v1/targets/resolve").header("X-Service-Token","invalid-notification-credential")
            .contentType("application/json").content("{\"type\":\"URL\",\"value\":\"https://watch.example/\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/targets/resolve").header("X-Service-Token","notification-private-target-credential-at-least-32")
            .contentType("application/json").content("{\"type\":\"MESSAGE_TEMPLATE\",\"value\":\"private message\"}"))
            .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("TARGET_CALLER_FORBIDDEN"))
            .andExpect(jsonPath("$.correlationId").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
        mvc.perform(post("/internal/v1/targets/resolve").header("X-Service-Token","notification-private-target-credential-at-least-32")
            .contentType("application/json").content("{\"type\":\"URL\",\"value\":\"https://watch.example/\",\"scanId\":\"00000000-0000-4000-8000-000000000001\",\"reporterUserId\":\"00000000-0000-4000-8000-000000000002\"}"))
            .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("TARGET_CALLER_FORBIDDEN"));
    }
    @Autowired org.springframework.jdbc.core.JdbcTemplate searchJdbc;
    @Autowired com.baotin.threat.infrastructure.TargetSearchEvents searchEvents;
    @Test void searchSnapshotsShareTargetTransactionAndExcludePrivateInput() {
        var target=service.resolve("URL","https://search-publish.example.com/private?token=opaque-value");
        var rows=searchJdbc.queryForList("SELECT payload::text AS body FROM threat.outbox_events WHERE topic='baotin.threat.search.events.v1' AND aggregate_id=?",target.id());
        assertFalse(rows.isEmpty());String body=(String)rows.getFirst().get("body");
        assertTrue(body.contains("threat.created"));assertTrue(body.contains("search-publish.example.com"));
        assertFalse(body.contains("opaque-value"));assertFalse(body.contains("/private"));
        String privateText="Private message containing personal data";
        var template=service.resolve("MESSAGE_TEMPLATE",privateText);
        String templateEvent=searchJdbc.queryForObject("SELECT payload::text FROM threat.outbox_events WHERE topic='baotin.threat.search.events.v1' AND aggregate_id=?",String.class,template.id());
        assertFalse(templateEvent.contains(privateText));assertTrue(templateEvent.contains("MESSAGE_TEMPLATE"));
        java.util.UUID aborted=java.util.UUID.randomUUID();
        var transactions=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(searchJdbc.getDataSource()));
        assertThrows(IllegalStateException.class,()->transactions.execute(status->{
            searchEvents.publish("threat.created",aborted,"URL","https://rollback.example.com/private",java.time.Instant.now(),java.util.UUID.randomUUID());
            throw new IllegalStateException("synthetic rollback");
        }));
        assertEquals(0,searchJdbc.queryForObject("SELECT count(*) FROM threat.outbox_events WHERE aggregate_id=?",Integer.class,aborted));
    }
}
