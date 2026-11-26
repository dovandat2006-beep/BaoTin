package com.baotin.notification;

import com.baotin.notification.application.EventIngestor;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WatchlistTargetIntegrationTests {
    static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17-alpine");
    static final UUID TARGET=UUID.fromString("00000000-0000-4000-8000-000000000077");
    static final UUID DISPLAY_TARGET=UUID.fromString("00000000-0000-4000-8000-000000000078");
    static final AtomicInteger STATUS=new AtomicInteger(200);
    static final HttpServer THREAT;
    static final String TOKEN="notification-target-test-credential-at-least-32";
    static {
        try {
            PG.start();THREAT=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            THREAT.createContext("/internal/v1/targets/resolve",e->{
                int status=TOKEN.equals(e.getRequestHeaders().getFirst("X-Service-Token"))?STATUS.get():401;
                String request=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                UUID target=request.contains("?a=1&b=2")?DISPLAY_TARGET:TARGET;
                byte[] body=("{\"id\":\""+target+"\",\"type\":\"URL\",\"displayValue\":\"https://canonical.example/\"}").getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,body.length);
                try(var out=e.getResponseBody()){out.write(body);}
            });THREAT.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);r.add("spring.datasource.password",PG::getPassword);
        r.add("spring.flyway.user",PG::getUsername);r.add("spring.flyway.password",PG::getPassword);
        r.add("notification.threat-uri",()->"http://127.0.0.1:"+THREAT.getAddress().getPort());r.add("notification.threat-token",()->TOKEN);
    }
    @Autowired MockMvc mvc;
    @Autowired EventIngestor ingestor;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void clean(){STATUS.set(200);jdbc.execute("TRUNCATE notification.delivery_attempts,notification.notifications,notification.watchlist,notification.preferences,notification.inbox_events,notification.outbox_events CASCADE");}
    @AfterAll static void stop(){THREAT.stop(0);PG.stop();}

    @Test void realThreatIdentityReceivesCanonicalReputationEventsExactlyOnce() throws Exception {
        UUID user=UUID.randomUUID();
        mvc.perform(post("/api/v1/watchlist").with(jwt().jwt(j->j.subject(user.toString()))).contentType("application/json").content("{\"url\":\"https://canonical.example/\"}"))
            .andExpect(status().isCreated());
        var payload=Map.of("threatTargetId",TARGET,"targetType","URL","oldScore",0,"newScore",91,"oldLevel","LOW","newLevel","CRITICAL","reason","moderator verification");
        var event=Map.of("eventId",UUID.randomUUID(),"eventType","reputation.score.changed","eventVersion",1,"occurredAt",Instant.now(),"payload",payload);
        String raw=json.writeValueAsString(event);ingestor.ingest(raw);ingestor.ingest(raw);
        mvc.perform(get("/api/v1/watchlist").with(jwt().jwt(j->j.subject(user.toString()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].score").value(91)).andExpect(jsonPath("$.items[0].level").value("CRITICAL"));
        String notifications=mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j->j.subject(user.toString())))).andReturn().getResponse().getContentAsString();
        long alerts=json.readTree(notifications).path("items").valueStream().filter(n->n.path("type").asText().equals("WATCHLIST_RISK_CHANGED")).count();
        assertThat(alerts).isEqualTo(1);
        mvc.perform(get("/api/v1/watchlist").with(jwt().jwt(j->j.subject(UUID.randomUUID().toString())))).andExpect(jsonPath("$.items.length()").value(0));
    }
    @Test void configuredThreatFailureNeverCreatesAnUnboundWatch() throws Exception {
        STATUS.set(503);UUID user=UUID.randomUUID();
        mvc.perform(post("/api/v1/watchlist").with(jwt().jwt(j->j.subject(user.toString()))).contentType("application/json").content("{\"url\":\"https://canonical.example/\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/v1/watchlist").with(jwt().jwt(j->j.subject(user.toString())))).andExpect(jsonPath("$.items.length()").value(0));
    }
    @Test void bothSubmittedAndDisplayedQueryOrderReceiveReputationUpdates() throws Exception {
        UUID user=UUID.randomUUID();String body="{\"url\":\"https://canonical.example/?b=2&a=1\"}";
        String first=mvc.perform(post("/api/v1/watchlist").with(jwt().jwt(j->j.subject(user.toString()))).contentType("application/json").content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(first).path("url").asText()).isEqualTo("https://canonical.example/?a=1&b=2");
        mvc.perform(post("/api/v1/watchlist").with(jwt().jwt(j->j.subject(user.toString()))).contentType("application/json").content(body)).andExpect(status().isCreated());
        int score=40;
        for(UUID target:new UUID[]{TARGET,DISPLAY_TARGET}){
            var event=Map.of("eventId",UUID.randomUUID(),"eventType","reputation.score.changed","eventVersion",1,
                "payload",Map.of("threatTargetId",target,"targetType","URL","newScore",score,"newLevel",score==40?"MEDIUM":"CRITICAL"));
            ingestor.ingest(json.writeValueAsString(event));
            mvc.perform(get("/api/v1/watchlist").with(jwt().jwt(j->j.subject(user.toString()))))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].score").value(score))
                .andExpect(jsonPath("$.items[0].id").value(json.readTree(first).path("id").asText()));
            score=91;
        }
    }
}
