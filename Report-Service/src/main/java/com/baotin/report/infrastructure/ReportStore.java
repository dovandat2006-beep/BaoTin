package com.baotin.report.infrastructure;

import com.baotin.report.api.*;
import com.baotin.report.api.ReportModels.*;
import com.baotin.report.domain.ReportStatus;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportStore {
    private final JdbcTemplate jdbc;
    public ReportStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public JdbcTemplate jdbc() { return jdbc; }
    public Report report(UUID id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM report.reports WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::map, id);
        if (rows.isEmpty()) throw ReportFailure.missing();
        return rows.getFirst();
    }
    private Report map(ResultSet rs, int row) throws SQLException {
        UUID id=rs.getObject("id", UUID.class);
        return new Report(id, rs.getObject("reporter_user_id",UUID.class), rs.getObject("target_id",UUID.class),
            rs.getString("target_type"),rs.getString("target_display"),rs.getString("category"),rs.getString("title"),
            rs.getString("description"),time(rs,"occurred_at"),ReportStatus.valueOf(rs.getString("status")),
            rs.getString("priority"),rs.getString("moderator_note"),time(rs,"created_at"),time(rs,"updated_at"),
            rs.getLong("version"),evidence(id),history(id),rs.getObject("scan_id",UUID.class));
    }
    private static Instant time(ResultSet rs,String name) throws SQLException { var t=rs.getTimestamp(name); return t==null?null:t.toInstant(); }
    public List<Evidence> evidence(UUID id) {
        return jdbc.query("SELECT * FROM report.evidence WHERE report_id=? AND deleted_at IS NULL ORDER BY created_at,id",
            (rs,n)->new Evidence(rs.getObject("id",UUID.class),rs.getString("original_filename"),rs.getString("mime_type"),
                rs.getLong("size_bytes"),rs.getString("sha256"),time(rs,"created_at")),id);
    }
    public List<History> history(UUID id) {
        return jdbc.query("SELECT * FROM report.status_history WHERE report_id=? ORDER BY created_at,id",
            (rs,n)->new History(rs.getString("from_status"),rs.getString("to_status"),rs.getObject("changed_by_user_id",UUID.class),rs.getString("note"),time(rs,"created_at")),id);
    }
    public Page<Report> page(UUID owner, String status, int page, int size) {
        String where=owner!=null?"reporter_user_id=?":"status=?";
        Object value=owner!=null?owner:status;
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM report.reports WHERE "+where,Long.class,value));
        var items=jdbc.query("SELECT * FROM report.reports WHERE "+where+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",this::map,value,size,(long)page*size);
        return new Page<>(items,page,size,total,(total+size-1)/size);
    }
    public AnalyticsModerationView analyticsModeration(String status, String priority, int page, int size, boolean ascending) {
        var where=new StringBuilder(" WHERE 1=1");
        var filters=new ArrayList<Object>();
        if(status!=null) {where.append(" AND r.status=?");filters.add(status);}
        if(priority!=null) {where.append(" AND r.priority=?");filters.add(priority);}
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM report.reports r"+where,Long.class,filters.toArray()));
        var params=new ArrayList<>(filters);params.add(size);params.add((long)page*size);
        var items=jdbc.query("SELECT r.id,r.target_type,r.target_display,r.category,r.title,r.status,r.priority,r.created_at,r.moderator_note,"
                +"(SELECT COUNT(*) FROM report.evidence e WHERE e.report_id=r.id AND e.deleted_at IS NULL) AS evidence_count "
                +"FROM report.reports r"+where+" ORDER BY r.created_at "+(ascending?"ASC":"DESC")+", r.id ASC LIMIT ? OFFSET ?",
            (rs,n)->new AnalyticsModerationView.Item(rs.getObject("id",UUID.class),rs.getString("target_type"),
                rs.getString("target_display"),rs.getString("category"),rs.getString("title"),rs.getString("status"),
                rs.getString("priority"),time(rs,"created_at"),rs.getInt("evidence_count"),rs.getString("moderator_note")),
            params.toArray());
        return new AnalyticsModerationView(items,page,size,total,(total+size-1)/size,
            new AnalyticsModerationView.Counts(
                counts("SELECT status AS k,COUNT(*) AS n FROM report.reports GROUP BY status",Arrays.stream(ReportStatus.values()).map(Enum::name).toList()),
                counts("SELECT priority AS k,COUNT(*) AS n FROM report.reports GROUP BY priority",List.of("LOW","MEDIUM","HIGH")),
                resolvedToday()));
    }
    private Map<String,Long> counts(String sql,List<String> keys) {
        Map<String,Long> result=new LinkedHashMap<>();
        for(String key:keys) result.put(key,0L);
        for(var row:jdbc.queryForList(sql)) result.put((String)row.get("k"),((Number)row.get("n")).longValue());
        return result;
    }
    private long resolvedToday() {
        var today=LocalDate.now(ZoneOffset.UTC);
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM report.reports WHERE status IN ('VERIFIED','REJECTED') AND reviewed_at>=? AND reviewed_at<?",
            Long.class,Timestamp.from(today.atStartOfDay().toInstant(ZoneOffset.UTC)),Timestamp.from(today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        return count==null?0:count;
    }
}
