package com.baotin.report.api;

public class ReportFailure extends RuntimeException {
    public final int status;
    public final String code;
    public ReportFailure(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public static ReportFailure missing() { return new ReportFailure(404, "NOT_FOUND", "Không tìm thấy báo cáo hoặc bằng chứng."); }
}
