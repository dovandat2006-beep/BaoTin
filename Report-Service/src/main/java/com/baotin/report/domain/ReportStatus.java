package com.baotin.report.domain;

import com.baotin.report.api.ReportFailure;

public enum ReportStatus {
    SUBMITTED, PENDING_REVIEW, NEED_MORE_INFO, VERIFIED, REJECTED;

    public void requireTransition(ReportStatus next) {
        boolean allowed = switch (this) {
            case SUBMITTED, NEED_MORE_INFO -> next == PENDING_REVIEW;
            case PENDING_REVIEW -> next == NEED_MORE_INFO || next == VERIFIED || next == REJECTED;
            case VERIFIED, REJECTED -> false;
        };
        if (!allowed) throw new ReportFailure(409, "INVALID_TRANSITION", "Trạng thái báo cáo đã thay đổi. Hãy tải lại.");
    }

    public boolean editable() { return this == SUBMITTED || this == NEED_MORE_INFO; }
}
