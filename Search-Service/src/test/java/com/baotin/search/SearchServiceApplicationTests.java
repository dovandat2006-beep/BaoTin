package com.baotin.search;

import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchServiceApplicationTests {
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");
    static {
        DB.start();
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            s.execute("CREATE EXTENSION pg_trgm; CREATE SCHEMA search");
        } catch(SQLException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);
        p.add("spring.datasource.password",DB::getPassword);p.add("spring.flyway.enabled",()->true);
        p.add("search.events.enabled",()->false);
    }
    @Autowired Projection projection;
    @Autowired SearchStore store;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    final UUID id=UUID.fromString("10000000-0000-4000-8000-000000000001");
    @BeforeEach void clear() { jdbc.execute("TRUNCATE search.search_documents,search.inbox_events"); }
    String target(UUID event,String at,String host) {
        return """
            {"eventId":"%s","eventType":"search.target.updated","eventVersion":1,
            "producer":"threat-service","aggregateType":"ThreatTarget","aggregateId":"%s",
            "occurredAt":"%s","payload":{"targetId":"%s","targetType":"URL","displayValue":"%s"}}
            """.formatted(event,id,at,id,host);
    }
    String risk(UUID event,String at,int score,String level) {
        return """
            {"eventId":"%s","eventType":"reputation.score.changed","eventVersion":1,
            "producer":"reputation-service","aggregateType":"ThreatTarget","aggregateId":"%s",
            "payload":{"threatTargetId":"%s","targetType":"URL","changedAt":"%s","newScore":%d,"newLevel":"%s"}}
            """.formatted(event,id,id,at,score,level);
    }
    void seed() { projection.ingest("baotin.search.targets.v1",target(UUID.randomUUID(),"2026-11-11T00:00:00Z","example.com")); }
    SearchStore.Page find(String q) { return store.find(new SearchQuery(q,null,null,0,20)); }
    @Test void anonymousHttpAndPrivacy() throws Exception {
        seed(); var r=get("/api/v1/search?q=example");assertEquals(200,r.statusCode());
        assertTrue(r.body().contains("example.com"));assertTrue(r.body().contains("\"metadata\":{}"));
        assertTrue(r.body().contains("\"riskLevel\":null"));assertFalse(r.body().contains("targetEvent"));
    }
    @Test void duplicatesAndOutOfOrderRisk() {
        UUID event=UUID.randomUUID();String newer=risk(event,"2026-11-11T02:00:00Z",85,"CRITICAL");
        projection.ingest("baotin.reputation.events.v1",newer);projection.ingest("baotin.reputation.events.v1",newer);
        assertEquals(0,find("example").totalItems());seed();
        projection.ingest("baotin.reputation.events.v1",risk(UUID.randomUUID(),"2026-11-11T01:00:00Z",20,"LOW"));
        assertEquals(85d,find("example").items().getFirst().riskScore());
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM search.inbox_events",Integer.class));
    }
    @Test void targetOrderDoesNotEraseRisk() {
        seed();projection.ingest("baotin.reputation.events.v1",risk(UUID.randomUUID(),"2026-11-11T01:00:00Z",85,"CRITICAL"));
        projection.ingest("baotin.search.targets.v1",target(UUID.randomUUID(),"2026-11-10T00:00:00Z","old.example.com"));
        assertEquals("example.com",find("example").items().getFirst().title());
        assertEquals(85d,find("example").items().getFirst().riskScore());
    }
    @Test void filtersAndTypo() {
        seed();assertEquals(1,find("exampel.com").totalItems());
        assertEquals(0,store.find(new SearchQuery("example","URL","HIGH",0,20)).totalItems());
        projection.ingest("baotin.reputation.events.v1",risk(UUID.randomUUID(),"2026-11-11T01:00:00Z",70,"HIGH"));
        assertEquals(1,store.find(new SearchQuery("example","URL","HIGH",0,20)).totalItems());
        assertEquals(0,store.find(new SearchQuery("example",null,null,1,1)).items().size());
        assertEquals(1,store.find(new SearchQuery("example",null,null,1,1)).totalPages());
    }
    @Test void vietnameseNormalization() { assertEquals("lua dao dien thoai",SearchQuery.normalize("Lừa Đảo Điện Thoại")); }
    @Test void boundedInputsAndInjection() throws Exception {
        for(String query:List.of("q=x","q=abc&size=51","q=abc&page=-1","q=abc&page=1001","q=abc&type=SMS","q=abc&riskLevel=SAFE","q=abc&page=no")) {
            var r=get("/api/v1/search?"+query);assertEquals(400,r.statusCode(),query);
            assertTrue(r.headers().firstValue("content-type").orElse("").contains("application/problem+json"));
        }
        assertEquals(0,find("' OR 1=1 --").totalItems());
    }
    @Test void denialAndProbeBoundary() throws Exception {
        assertEquals(403,get("/internal/v1/rebuild").statusCode());
        assertEquals(403,get("/actuator/env").statusCode());
        assertEquals(200,get("/actuator/health/liveness").statusCode());
        assertEquals(200,get("/actuator/health/readiness").statusCode());
        var r=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/search"))
            .header("X-User-Role","ADMIN").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(403,r.statusCode());
    }
    @Test void hostParsingIsBoundedWithoutRecursiveRegex() {
        assertFalse(Projection.publicHost("a.".repeat(50000)+"com"));
        assertFalse(Projection.publicHost("a".repeat(64)+".com"));
        assertFalse(Projection.publicHost("-bad.example.com"));
        assertTrue(Projection.publicHost("a".repeat(63)+".com"));
    }
    @Test void invalidEventsHaveNoEffect() {
        for(String host:List.of("example.com/private?token=secret","127.0.0.1","local.internal","USER@example.com","Example.com")) {
            assertThrows(IllegalArgumentException.class,()->projection.ingest("baotin.search.targets.v1",target(UUID.randomUUID(),"2026-11-11T00:00:00Z",host)));
        }
        assertThrows(IllegalArgumentException.class,()->projection.ingest("wrong-topic",target(UUID.randomUUID(),"2026-11-11T00:00:00Z","example.com")));
        assertThrows(IllegalArgumentException.class,()->projection.ingest("baotin.reputation.events.v1",risk(UUID.randomUUID(),"2026-11-11T00:00:00Z",101,"HIGH")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM search.inbox_events",Integer.class));
    }
    @Test void runtimeRoleHasNoCrossSchemaOrDdlRights() throws Exception {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS private_fixture; CREATE TABLE IF NOT EXISTS private_fixture.protected(id int)");
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='search_test_runtime') THEN CREATE ROLE search_test_runtime; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA search TO search_test_runtime; GRANT SELECT,INSERT,UPDATE ON search.search_documents TO search_test_runtime");
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var s=c.createStatement()) {
            s.execute("SET ROLE search_test_runtime");
            assertThrows(SQLException.class,()->s.execute("SELECT * FROM private_fixture.protected"));
            assertThrows(SQLException.class,()->s.execute("CREATE TABLE search.forbidden(id int)"));
            assertThrows(SQLException.class,()->s.execute("DELETE FROM search.search_documents"));
            assertTrue(s.execute("SELECT * FROM search.search_documents"));
        }
    }
    HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
}
