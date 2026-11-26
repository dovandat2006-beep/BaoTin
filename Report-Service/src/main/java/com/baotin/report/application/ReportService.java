package com.baotin.report.application;

import com.baotin.report.api.*;
import com.baotin.report.api.ReportModels.*;
import com.baotin.report.domain.*;
import com.baotin.report.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReportService {
    private final ReportStore store;
    private final TargetClient targets;
    private final EvidenceStorage storage;
    private final ObjectMapper json;
    private final io.micrometer.core.instrument.Counter submitted;
    public ReportService(ReportStore store,TargetClient targets,EvidenceStorage storage,ObjectMapper json,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.store=store;this.targets=targets;this.storage=storage;this.json=json;
        submitted=metrics.counter("baotin_report_total");
    }
    @Transactional
    public Report create(UUID user,UUID key,Create input,String correlation) {
        if(input.title().trim().length()<6 || input.description().trim().length()<16) throw new ReportFailure(400,"INVALID_INPUT","Tiêu đề cần ít nhất 6 ký tự, mô tả cần ít nhất 16 ký tự.");
        // Keep hashes of pre-scanId requests stable when the optional reference is absent.
        var canonical=(tools.jackson.databind.node.ObjectNode)json.valueToTree(input);
        if(input.scanId()==null) canonical.remove("scanId");
        String hash=EvidencePolicy.hash(json.writeValueAsBytes(canonical));
        // PostgreSQL waits for a competing insertion before checking the existing response.
        store.jdbc().update("INSERT INTO report.idempotency_keys(user_id,idempotency_key,request_hash,created_at) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",user,key,hash,now());
        var saved=store.jdbc().queryForMap("SELECT * FROM report.idempotency_keys WHERE user_id=? AND idempotency_key=? FOR UPDATE",user,key);
        if(!hash.equals(saved.get("request_hash"))) throw new ReportFailure(409,"IDEMPOTENCY_CONFLICT","Khóa gửi đã được dùng cho nội dung khác.");
        if(saved.get("response_body")!=null) return json.readValue(saved.get("response_body").toString(),Report.class);
        var target=input.scanId()==null?targets.resolve(input.targetType(),input.targetValue()):targets.resolve(input.targetType(),input.targetValue(),input.scanId(),user);
        UUID id=UUID.randomUUID();Timestamp time=now();
        store.jdbc().update("INSERT INTO report.reports(id,reporter_user_id,target_id,target_type,target_display,category,title,description,occurred_at,status,created_at,updated_at,scan_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,user,target.id(),target.type(),target.displayValue(),input.category(),input.title().trim(),input.description().trim(),input.occurredAt()==null?null:Timestamp.from(input.occurredAt()),"SUBMITTED",time,time,input.scanId());
        history(id,null,ReportStatus.SUBMITTED,user,null);
        var result=store.report(id,false);
        event(result,"report.submitted",correlation);
        store.jdbc().update("UPDATE report.idempotency_keys SET report_id=?,response_body=? WHERE user_id=? AND idempotency_key=?",id,json.writeValueAsString(result),user,key);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { submitted.increment(); }
        });
        return result;
    }
    public Report detail(UUID id,UUID user,boolean moderator) {
        var result=store.report(id,false);authorize(result,user,moderator);return result;
    }
    public Page<Report> mine(UUID user,int page,int size) { return store.page(user,null,page,size); }
    public Page<Report> queue(String status,int page,int size) {
        try { ReportStatus.valueOf(status); } catch(IllegalArgumentException e) { throw new ReportFailure(400,"INVALID_STATUS","Trạng thái không hợp lệ."); }
        return store.page(null,status,page,size);
    }
    @Transactional
    public Report review(UUID id,UUID moderator,ReportStatus next,Decision decision,String correlation) {
        var report=store.report(id,true);
        if(report.reporterUserId().equals(moderator)) throw new ReportFailure(403,"SELF_REVIEW","Không được kiểm duyệt báo cáo của chính mình.");
        requireVersion(report,decision.version());report.status().requireTransition(next);
        store.jdbc().update("UPDATE report.reports SET status=?,moderator_user_id=?,moderator_note=?,reviewed_at=?,updated_at=?,version=version+1 WHERE id=?",next.name(),moderator,decision.note().trim(),now(),now(),id);
        history(id,report.status(),next,moderator,decision.note().trim());
        var result=store.report(id,false);
        if(next!=ReportStatus.PENDING_REVIEW) event(result,switch(next) {
            case VERIFIED -> "report.verified";case REJECTED -> "report.rejected";case NEED_MORE_INFO -> "report.need_more_info";
            default -> throw new IllegalStateException();
        },correlation);
        return result;
    }
    @Transactional
    public Report supplement(UUID id,UUID user,Supplement input) {
        var report=store.report(id,true);authorize(report,user,false);requireVersion(report,input.version());
        if(report.status()!=ReportStatus.NEED_MORE_INFO) throw new ReportFailure(409,"INVALID_TRANSITION","Chỉ bổ sung báo cáo đang cần thông tin.");
        report.status().requireTransition(ReportStatus.PENDING_REVIEW);
        store.jdbc().update("UPDATE report.reports SET status='PENDING_REVIEW',updated_at=?,version=version+1 WHERE id=?",now(),id);
        history(id,report.status(),ReportStatus.PENDING_REVIEW,user,input.note().trim());
        return store.report(id,false);
    }
    @Transactional
    public Evidence upload(UUID id,UUID user,byte[] bytes,String mime,String filename) {
        var report=store.report(id,true);authorize(report,user,false);requireEditable(report);
        if(report.evidence().size()>=5) throw new ReportFailure(409,"EVIDENCE_LIMIT","Mỗi báo cáo tối đa 5 bằng chứng.");
        String actual=EvidencePolicy.mime(bytes,mime),hash=EvidencePolicy.hash(bytes);
        if(report.evidence().stream().anyMatch(e->e.sha256().equals(hash))) throw new ReportFailure(409,"EVIDENCE_DUPLICATE","Bằng chứng này đã được tải lên.");
        UUID evidenceId=UUID.randomUUID();String key="reports/"+id+"/"+evidenceId+switch(actual) { case "image/png"->".png";case "image/jpeg"->".jpg";case "image/webp"->".webp";default->".pdf"; };
        storage.put(key,bytes,actual);
        // Rollback compensation keeps objects out of private storage when metadata fails.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status==STATUS_ROLLED_BACK) try { storage.remove(key); } catch(ReportFailure e) {
                    org.slf4j.LoggerFactory.getLogger(ReportService.class).warn("Evidence rollback cleanup failed; reconcile private orphan objects");
                }
            }
        });
        store.jdbc().update("INSERT INTO report.evidence(id,report_id,object_key,original_filename,mime_type,size_bytes,sha256,created_at) VALUES(?,?,?,?,?,?,?,?)",evidenceId,id,key,EvidencePolicy.filename(filename),actual,bytes.length,hash,now());
        touch(id);
        return store.evidence(id).stream().filter(e->e.id().equals(evidenceId)).findFirst().orElseThrow();
    }
    @Transactional
    public void deleteEvidence(UUID reportId,UUID evidenceId,UUID user) {
        var report=store.report(reportId,true);authorize(report,user,false);requireEditable(report);
        if(store.jdbc().update("UPDATE report.evidence SET deleted_at=? WHERE id=? AND report_id=? AND deleted_at IS NULL",now(),evidenceId,reportId)!=1) throw ReportFailure.missing();
        touch(reportId);
    }
    public record Download(Evidence evidence,java.io.InputStream stream) {}
    public Download download(UUID reportId,UUID evidenceId,UUID user,boolean moderator) {
        var report=detail(reportId,user,moderator);
        var evidence=report.evidence().stream().filter(e->e.id().equals(evidenceId)).findFirst().orElseThrow(ReportFailure::missing);
        String key=store.jdbc().queryForObject("SELECT object_key FROM report.evidence WHERE id=? AND report_id=? AND deleted_at IS NULL",String.class,evidenceId,reportId);
        return new Download(evidence,storage.open(key));
    }
    private void touch(UUID id) { store.jdbc().update("UPDATE report.reports SET updated_at=?,version=version+1 WHERE id=?",now(),id); }
    private static void authorize(Report report,UUID user,boolean moderator) {
        if(!moderator && !report.reporterUserId().equals(user)) throw ReportFailure.missing();
    }
    private static void requireEditable(Report report) {
        if(!report.status().editable()) throw new ReportFailure(409,"REPORT_LOCKED","Chỉ thay đổi bằng chứng khi đã gửi hoặc đang cần bổ sung.");
    }
    private static void requireVersion(Report report,long version) {
        if(report.version()!=version) throw new ReportFailure(409,"VERSION_CONFLICT","Báo cáo đã thay đổi. Hãy tải lại trước khi tiếp tục.");
    }
    private void history(UUID id,ReportStatus from,ReportStatus to,UUID user,String note) {
        store.jdbc().update("INSERT INTO report.status_history(id,report_id,from_status,to_status,changed_by_user_id,note,created_at) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),id,from==null?null:from.name(),to.name(),user,note,now());
    }
    private void event(Report report,String type,String correlation) {
        UUID eventId=UUID.randomUUID();Instant time=Instant.now();
        var payload=Map.of("reportId",report.id(),"targetId",report.threatTargetId(),"targetType",report.targetTypeSnapshot(),"reporterUserId",report.reporterUserId(),"status",report.status().name(),"version",report.version());
        var envelope=new LinkedHashMap<String,Object>();
        envelope.put("eventId",eventId);envelope.put("eventType",type);envelope.put("eventVersion",1);envelope.put("occurredAt",time);
        envelope.put("producer","report-service");envelope.put("aggregateType","Report");envelope.put("aggregateId",report.id());
        envelope.put("correlationId",correlation);envelope.put("causationId",null);envelope.put("payload",payload);
        store.jdbc().update("INSERT INTO report.outbox_events(id,aggregate_id,event_type,payload,created_at,next_attempt_at) VALUES(?,?,?,?,?,?)",eventId,report.id(),type,json.writeValueAsString(envelope),Timestamp.from(time),Timestamp.from(time));
    }
    private static Timestamp now() { return Timestamp.from(Instant.now()); }
}
