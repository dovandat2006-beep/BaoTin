package com.baotin.report.api;

import com.baotin.report.domain.ReportStatus;
import com.baotin.report.infrastructure.ReportStore;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
public class AnalyticsModerationController {
    private final ReportStore store;
    public AnalyticsModerationController(ReportStore store) { this.store=store; }
    @GetMapping("/internal/v1/analytics/moderation")
    public AnalyticsModerationView moderation(
            @RequestParam(required=false) String status,
            @RequestParam(required=false) String priority,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,
            @RequestParam(defaultValue="submittedAt,desc") String sort) {
        if(page<0||page>10000||size<1||size>100) throw new ReportFailure(400,"INVALID_MODERATION_FILTER","Invalid paging.");
        String statusFilter=parseStatus(status);
        String priorityFilter=parsePriority(priority);
        boolean ascending=parseSort(sort);
        return store.analyticsModeration(statusFilter,priorityFilter,page,size,ascending);
    }
    private static String parseStatus(String value) {
        if(value==null||value.isBlank()) return null;
        try { return ReportStatus.valueOf(value).name(); }
        catch(IllegalArgumentException e) { throw new ReportFailure(400,"INVALID_MODERATION_FILTER","Invalid status."); }
    }
    private static String parsePriority(String value) {
        if(value==null||value.isBlank()) return null;
        if(!Set.of("LOW","MEDIUM","HIGH").contains(value)) throw new ReportFailure(400,"INVALID_MODERATION_FILTER","Invalid priority.");
        return value;
    }
    private static boolean parseSort(String value) {
        if("submittedAt,asc".equals(value)) return true;
        if("submittedAt,desc".equals(value)) return false;
        throw new ReportFailure(400,"INVALID_MODERATION_FILTER","Invalid sort.");
    }
}
