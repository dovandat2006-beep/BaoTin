package com.baotin.report;

import com.baotin.report.api.ReportModels.*;
import com.baotin.report.application.ReportService;
import com.baotin.report.domain.*;
import com.baotin.report.infrastructure.*;
import com.baotin.report.api.ReportFailure;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class ReportWorkflowTests {
    @Autowired ReportService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @MockitoBean TargetClient targets;
    @MockitoBean EvidenceStorage storage;
    @MockitoBean org.springframework.kafka.core.KafkaTemplate<String,String> kafka;
    @Autowired OutboxPublisher publisher;
    final UUID owner=UUID.randomUUID(),moderator=UUID.randomUUID(),target=UUID.randomUUID();
    final String correlation=UUID.randomUUID().toString();
    @BeforeEach void resetDatabase() {
        for(String table:List.of("evidence","status_history","idempotency_keys","outbox_events","reports")) jdbc.update("DELETE FROM report."+table);
        when(targets.resolve(anyString(),anyString())).thenReturn(new TargetClient.Target(target,"URL","https://example.test/"));
        when(storage.open(anyString())).thenAnswer(i->new ByteArrayInputStream(png(1)));
    }
    Create draft() {return new Create("URL","https://example.test/","PHISHING_URL","Trang giả mạo ngân hàng","Trang yêu cầu cung cấp mật khẩu và mã OTP.",null,null);}
    Report create() {return service.create(owner,UUID.randomUUID(),draft(),correlation);}
    @Test void absentScanReferencePreservesLegacyIdempotencyHash() {
        var input=draft();
        var legacy=new LinkedHashMap<String,Object>();
        legacy.put("targetType",input.targetType());legacy.put("targetValue",input.targetValue());legacy.put("category",input.category());
        legacy.put("title",input.title());legacy.put("description",input.description());legacy.put("occurredAt",input.occurredAt());
        UUID key=UUID.randomUUID();service.create(owner,key,input,correlation);
        assertEquals(EvidencePolicy.hash(json.writeValueAsBytes(legacy)),jdbc.queryForObject("SELECT request_hash FROM report.idempotency_keys WHERE user_id=? AND idempotency_key=?",String.class,owner,key));
    }
    @Test void committedReportMetricDoesNotCountReplaysOrRolledBackRequests() {
        double before=metrics.counter("baotin_report_total").count();UUID key=UUID.randomUUID();
        service.create(owner,key,draft(),correlation);service.create(owner,key,draft(),correlation);
        assertEquals(before+1,metrics.counter("baotin_report_total").count());
        when(targets.resolve(anyString(),anyString())).thenThrow(new ReportFailure(503,"TARGET_UNAVAILABLE","Unavailable"));
        assertThrows(ReportFailure.class,this::create);
        assertEquals(before+1,metrics.counter("baotin_report_total").count());
    }
    @Test void rejectedSourceScanLeavesNoReportOrIdempotencyReservation() {
        UUID scan=UUID.randomUUID();var input=draft();
        when(targets.resolve(input.targetType(),input.targetValue(),scan,owner)).thenThrow(new ReportFailure(404,"SOURCE_SCAN_NOT_FOUND","Missing"));
        var linked=new Create(input.targetType(),input.targetValue(),input.category(),input.title(),input.description(),null,scan);
        assertThrows(ReportFailure.class,()->service.create(owner,UUID.randomUUID(),linked,correlation));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM report.reports",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM report.idempotency_keys",Integer.class));
    }
    @Test void createPersistsSnapshotHistoryAndOutboxAtomically() {
        var r=create();assertEquals(ReportStatus.SUBMITTED,r.status());assertEquals(target,r.threatTargetId());assertEquals(1,r.history().size());
        var envelope=json.readTree(jdbc.queryForObject("SELECT payload FROM report.outbox_events",String.class));
        assertEquals("report.submitted",envelope.get("eventType").asString());assertEquals(target.toString(),envelope.get("payload").get("targetId").asString());
        assertFalse(envelope.toString().contains("mật khẩu"));
    }
    @Test void idempotencyReplaysOriginalResponseAndRejectsChangedBody() {
        UUID key=UUID.randomUUID();var original=service.create(owner,key,draft(),correlation);
        service.review(original.id(),moderator,ReportStatus.PENDING_REVIEW,new Decision(0L,"Bắt đầu"),correlation);
        assertEquals(original,service.create(owner,key,draft(),correlation));
        var changed=new Create("URL","https://other.test","OTHER","Báo cáo khác","Nội dung sự việc khác hoàn toàn.",null,null);
        assertEquals(409,assertThrows(ReportFailure.class,()->service.create(owner,key,changed,correlation)).status);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM report.reports",Integer.class));
    }
    @Test void concurrentDuplicateSubmissionCreatesOneReport() throws Exception {
        UUID key=UUID.randomUUID();try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->service.create(owner,key,draft(),correlation));var b=executor.submit(()->service.create(owner,key,draft(),correlation));
            assertEquals(a.get(10,TimeUnit.SECONDS).id(),b.get(10,TimeUnit.SECONDS).id());
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM report.outbox_events",Integer.class));
    }
    @Test void dependencyFailureRollsBackReportAndIdempotencyReservation() {
        when(targets.resolve(anyString(),anyString())).thenThrow(new ReportFailure(503,"TARGET_UNAVAILABLE","Unavailable"));
        assertThrows(ReportFailure.class,this::create);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM report.idempotency_keys",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM report.outbox_events",Integer.class));
    }
    @Test void ownerIsolationAndSelfReviewDenied() {
        var r=create();assertEquals(404,assertThrows(ReportFailure.class,()->service.detail(r.id(),UUID.randomUUID(),false)).status);
        assertEquals(403,assertThrows(ReportFailure.class,()->service.review(r.id(),owner,ReportStatus.PENDING_REVIEW,new Decision(0L,"Tự duyệt"),correlation)).status);
        assertEquals(r.id(),service.detail(r.id(),moderator,true).id());
    }
    @Test void moderationRequiresReviewAndVersionAndTerminalStatesAreImmutable() {
        var r=create();assertThrows(ReportFailure.class,()->service.review(r.id(),moderator,ReportStatus.VERIFIED,new Decision(0L,"Xác minh"),correlation));
        var pending=service.review(r.id(),moderator,ReportStatus.PENDING_REVIEW,new Decision(0L,"Đọc bằng chứng"),correlation);
        assertThrows(ReportFailure.class,()->service.review(r.id(),moderator,ReportStatus.VERIFIED,new Decision(0L,"Phiên cũ"),correlation));
        var verified=service.review(r.id(),moderator,ReportStatus.VERIFIED,new Decision(pending.version(),"Đã xác minh bằng chứng"),correlation);
        assertThrows(ReportFailure.class,()->service.review(r.id(),moderator,ReportStatus.REJECTED,new Decision(verified.version(),"Không được đổi"),correlation));
        assertEquals(3,verified.history().size());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM report.outbox_events WHERE event_type='report.verified'",Integer.class));
    }
    @Test void concurrentModerationOnlyOneDecisionWins() throws Exception {
        var r=create();var pending=service.review(r.id(),moderator,ReportStatus.PENDING_REVIEW,new Decision(0L,"Đọc bằng chứng"),correlation);
        try(var executor=Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results=new ArrayList<>();
            for(var next:List.of(ReportStatus.VERIFIED,ReportStatus.REJECTED)) results.add(executor.submit(()->{
                try {service.review(r.id(),moderator,next,new Decision(pending.version(),"Quyết định"),correlation);return true;}
                catch(ReportFailure e) {assertEquals(409,e.status);return false;}
            }));
            int wins=0;for(var result:results) if(result.get(10,TimeUnit.SECONDS)) wins++;assertEquals(1,wins);
        }
    }
    @Test void requestInfoAllowsEvidenceThenResubmit() {
        var r=create();var pending=service.review(r.id(),moderator,ReportStatus.PENDING_REVIEW,new Decision(0L,"Bắt đầu"),correlation);
        var needed=service.review(r.id(),moderator,ReportStatus.NEED_MORE_INFO,new Decision(pending.version(),"Cần ảnh chụp"),correlation);
        service.upload(r.id(),owner,png(1),"image/png","capture.png");
        assertThrows(ReportFailure.class,()->service.supplement(r.id(),owner,new Supplement(needed.version(),"Phiên cũ")));
        var current=service.detail(r.id(),owner,false);
        var result=service.supplement(r.id(),owner,new Supplement(current.version(),"Đã bổ sung ảnh chụp"));
        assertEquals(ReportStatus.PENDING_REVIEW,result.status());assertEquals(1,result.evidence().size());
        assertThrows(ReportFailure.class,()->service.upload(r.id(),owner,png(2),"image/png","late.png"));
    }
    @Test void uploadEnforcesOwnershipMagicSizeCountAndDuplicate() {
        var r=create();assertThrows(ReportFailure.class,()->service.upload(r.id(),moderator,png(1),"image/png","a.png"));
        assertThrows(ReportFailure.class,()->service.upload(r.id(),owner,"script".getBytes(),"image/png","a.png"));
        assertThrows(ReportFailure.class,()->service.upload(r.id(),owner,png(1),"application/pdf","a.pdf"));
        assertThrows(ReportFailure.class,()->service.upload(r.id(),owner,new byte[EvidencePolicy.MAX_BYTES+1],"image/png","big.png"));
        for(int i=0;i<5;i++) service.upload(r.id(),owner,png(i),"image/png","../a.png");
        assertThrows(ReportFailure.class,()->service.upload(r.id(),owner,png(8),"image/png","a.png"));
        var current=service.detail(r.id(),owner,false);assertEquals(5,current.evidence().size());assertEquals("a.png",current.evidence().getFirst().originalFilename());
        verify(storage,times(5)).put(anyString(),any(),eq("image/png"));
    }
    @Test void duplicateFileAndDeletedEvidenceCannotBeDownloaded() {
        var r=create();var evidence=service.upload(r.id(),owner,png(1),"image/png","a.png");
        assertThrows(ReportFailure.class,()->service.upload(r.id(),owner,png(1),"image/png","same.png"));
        assertThrows(ReportFailure.class,()->service.download(r.id(),evidence.id(),moderator,false));
        assertNotNull(service.download(r.id(),evidence.id(),moderator,true).stream());
        service.deleteEvidence(r.id(),evidence.id(),owner);
        assertThrows(ReportFailure.class,()->service.download(r.id(),evidence.id(),owner,false));
        assertTrue(service.detail(r.id(),owner,false).evidence().isEmpty());
    }
    @Test void paginationOnlyListsOwnerReports() {
        create();assertEquals(1,service.mine(owner,0,20).totalItems());assertEquals(0,service.mine(UUID.randomUUID(),0,20).totalItems());
    }
    @Test void outboxFailureRetriesSameEnvelopeBeforeLaterAggregateEvents() {
        var r=create();
        var pending=service.review(r.id(),moderator,ReportStatus.PENDING_REVIEW,new Decision(0L,"Bắt đầu"),correlation);
        service.review(r.id(),moderator,ReportStatus.VERIFIED,new Decision(pending.version(),"Đã xác minh"),correlation);
        // Simulate a clock correction after creation; verified must never overtake submitted.
        jdbc.update("UPDATE report.outbox_events SET created_at=? WHERE event_type='report.verified'",java.sql.Timestamp.from(java.time.Instant.EPOCH));
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        publisher.publish();
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM report.outbox_events WHERE published_at IS NOT NULL",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM report.outbox_events WHERE event_type='report.submitted'",Integer.class));
        reset(kafka);
        publisher.publish();verifyNoInteractions(kafka);
        // A past instant avoids H2/host timestamp rounding making this retry a few microseconds early.
        jdbc.update("UPDATE report.outbox_events SET next_attempt_at=? WHERE event_type='report.submitted'",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)));
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        publisher.publish();publisher.publish();
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM report.outbox_events WHERE published_at IS NOT NULL",Integer.class));
    }
    static byte[] png(int unique) {return new byte[]{(byte)0x89,80,78,71,13,10,26,10,(byte)unique};}
}
