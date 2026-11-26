package com.baotin.report;

import com.baotin.report.infrastructure.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsModerationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TargetClient targets;
    @MockitoBean EvidenceStorage storage;
    @MockitoBean org.springframework.kafka.core.KafkaTemplate<String,String> kafka;

    static final String PATH="/internal/v1/analytics/moderation";
    // Anchored to the current UTC day so the resolvedToday assertions stay valid after 2026-11-16.
    static final Instant DAY=LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    static final Instant HIGH_AT=DAY.plusSeconds(9*3600);
    static final Instant LOW_AT=DAY.plusSeconds(10*3600);
    static final Instant VERIFIED_CREATED=DAY.plusSeconds(8*3600);
    static final Instant VERIFIED_AT=DAY.plusSeconds(11*3600);

    @BeforeEach void resetDatabase() {
        for(String table:List.of("evidence","status_history","idempotency_keys","outbox_events","reports")) jdbc.update("DELETE FROM report."+table);
    }
    UUID seed() {
        report("SUBMITTED","HIGH",HIGH_AT,null,null);
        UUID low=report("SUBMITTED","LOW",LOW_AT,null,null);
        report("VERIFIED","MEDIUM",VERIFIED_CREATED,VERIFIED_AT,"Evidence confirmed");
        return low;
    }
    UUID report(String status,String priority,Instant createdAt,Instant reviewedAt,String note) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO report.reports (id,reporter_user_id,target_id,target_type,target_display,category,title,description,status,priority,moderator_note,created_at,reviewed_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,UUID.randomUUID(),UUID.randomUUID(),"URL","https://example.test/","PHISHING_URL","Suspicious website",
            "The website requests banking credentials.",status,priority,note,java.sql.Timestamp.from(createdAt),
            reviewedAt==null?null:java.sql.Timestamp.from(reviewedAt),java.sql.Timestamp.from(createdAt));
        return id;
    }
    void evidence(UUID reportId,String name,Instant deletedAt) {
        jdbc.update("INSERT INTO report.evidence (id,report_id,object_key,original_filename,mime_type,size_bytes,sha256,created_at,deleted_at) VALUES (?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),reportId,"reports/"+reportId+"/"+name,name,"image/png",10L,"0".repeat(64),
            java.sql.Timestamp.from(HIGH_AT),deletedAt==null?null:java.sql.Timestamp.from(deletedAt));
    }
    static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    @Test void filtersReturnMatchingItemsAndGlobalCounts() throws Exception {
        seed();
        mvc.perform(get(PATH).param("status","SUBMITTED").param("priority","HIGH").param("page","0").param("size","20").param("sort","submittedAt,asc").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].status").value("SUBMITTED"))
            .andExpect(jsonPath("$.items[0].priority").value("HIGH"))
            .andExpect(jsonPath("$.counts.byStatus.SUBMITTED").value(2))
            .andExpect(jsonPath("$.counts.byStatus.REJECTED").value(0))
            .andExpect(jsonPath("$.counts.byPriority.HIGH").value(1))
            .andExpect(jsonPath("$.counts.resolvedToday").value(1));
    }
    @Test void descendingSortReturnsLaterReportFirst() throws Exception {
        UUID low=seed();
        mvc.perform(get(PATH).param("status","SUBMITTED").param("sort","submittedAt,desc").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(low.toString()));
    }
    @Test void pageBeyondLastReturnsEmptyItemsWithTotals() throws Exception {
        seed();
        mvc.perform(get(PATH).param("page","99").param("size","20").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.totalItems").value(3))
            .andExpect(jsonPath("$.totalPages").value(1));
    }
    @Test void evidenceCountIgnoresSoftDeletedRows() throws Exception {
        UUID id=report("NEED_MORE_INFO","MEDIUM",DAY.plusSeconds(12*3600),null,null);
        evidence(id,"keep.png",null);
        evidence(id,"gone.png",DAY.plusSeconds(13*3600));
        mvc.perform(get(PATH).param("status","NEED_MORE_INFO").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.items[0].evidenceCount").value(1));
    }
    @Test void resolvedTodayExcludesRowsBeforeUtcMidnight() throws Exception {
        report("VERIFIED","MEDIUM",VERIFIED_CREATED,VERIFIED_AT,null);
        report("VERIFIED","MEDIUM",DAY.minusSeconds(7200),DAY.minusSeconds(1),null);
        mvc.perform(get(PATH).with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.counts.resolvedToday").value(1));
    }
    @Test void invalidFiltersAreRejected() throws Exception {
        for(String[] filter:List.of(new String[]{"status","NOPE"},new String[]{"priority","CRITICAL"},new String[]{"sort","priority,desc"},new String[]{"page","-1"},new String[]{"size","0"},new String[]{"size","101"}))
            mvc.perform(get(PATH).param(filter[0],filter[1]).with(admin())).andExpect(status().isBadRequest());
    }
    @Test void defaultRequestReturnsGlobalCountsAndOneItem() throws Exception {
        seed();
        mvc.perform(get(PATH).param("size","1").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.totalItems").value(3))
            .andExpect(jsonPath("$.counts.byStatus.SUBMITTED").value(2))
            .andExpect(jsonPath("$.counts.byPriority.LOW").value(1));
    }
}
