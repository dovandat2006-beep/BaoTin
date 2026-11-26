package com.baotin.notification.application;

import com.baotin.notification.api.ApiFailure;
import com.baotin.notification.api.PageResponse;
import com.baotin.notification.api.WatchlistResource;
import com.baotin.notification.application.WatchlistStore.Row;
import com.baotin.notification.domain.RiskLevel;
import com.baotin.notification.domain.UrlCanonicalizer;
import com.baotin.notification.domain.UrlCanonicalizer.CanonicalUrl;
import com.baotin.notification.domain.UrlSafety;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WatchlistService {
    private final WatchlistStore watchlist;
    private final NotificationStore notifications;
    private final NotificationService notifier;
    private final BigDecimal minScoreDelta;
    private final com.baotin.notification.infrastructure.ThreatTargetClient targets;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public WatchlistService(WatchlistStore watchlist, NotificationStore notifications, NotificationService notifier,
                            @Value("${notification.risk.min-score-delta:10}") BigDecimal minScoreDelta,
                            com.baotin.notification.infrastructure.ThreatTargetClient targets,
                            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.watchlist = watchlist;
        this.notifications = notifications;
        this.notifier = notifier;
        this.minScoreDelta = minScoreDelta;
        this.targets = targets;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    public WatchlistResource add(UUID userId, String rawUrl) {
        CanonicalUrl canonical = UrlCanonicalizer.canonicalize(rawUrl);
        UrlSafety.rejectSecrets(canonical.value());
        // Finish bounded private HTTP work before taking database locks.
        UUID targetId=targets.resolve(rawUrl);
        // Notification sorts query pairs for display, while Threat intentionally
        // preserves order. Bind both submitted and displayed authoritative IDs.
        UUID displayTargetId=rawUrl.equals(canonical.value())?targetId:targets.resolve(canonical.value());
        return transactions.execute(transaction -> addBoundWatch(userId,canonical,targetId,displayTargetId));
    }

    private WatchlistResource addBoundWatch(UUID userId,CanonicalUrl canonical,UUID targetId,UUID displayTargetId) {
        WatchlistStore.AddResult result = watchlist.add(userId, canonical);
        if(targetId!=null)watchlist.bindTarget(result.row().id(),targetId);
        if(displayTargetId!=null && !displayTargetId.equals(targetId))watchlist.bindTarget(result.row().id(),displayTargetId);
        if (result.created()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("watchlistId", result.row().id().toString());
            data.put("url", result.row().canonicalUrl());
            data.put("urlTargetId", result.row().urlTargetId().toString());
            notifier.create(userId, "WATCHLIST_ADDED", "Đang theo dõi " + result.row().host(),
                "Bạn sẽ nhận thông báo khi điểm rủi ro của URL này thay đổi.", data, false);
        }
        return watchlist.resource(result.row());
    }

    public PageResponse<WatchlistResource> list(UUID userId, int page, int size) {
        List<WatchlistResource> items = watchlist.list(userId, page, size).stream().map(watchlist::resource).toList();
        return PageResponse.of(items, page, size, watchlist.count(userId));
    }

    public void remove(UUID userId, UUID id) {
        if (!watchlist.remove(userId, id)) throw ApiFailure.notFound();
    }

    /**
     * Applies a reputation.updated event. Matching by canonical hash means a
     * watch created before Threat-Service exists still receives later alerts.
     */
    @Transactional
    public int applyReputation(UUID targetId, String canonicalUrl, BigDecimal score, String level, String label) {
        String hash = null;
        if (canonicalUrl != null && !canonicalUrl.isBlank()) {
            try {
                hash = UrlCanonicalizer.canonicalize(canonicalUrl).hash();
            } catch (IllegalArgumentException ignored) {
                hash = null;
            }
        }
        if (targetId == null && hash == null) return 0;
        RiskLevel riskLevel = RiskLevel.parse(level);
        if (riskLevel == null) riskLevel = RiskLevel.fromScore(score);
        List<Row> rows = watchlist.applyReputation(targetId, hash, score,
            riskLevel == null ? null : riskLevel.name(), label);
        int created = 0;
        for (Row row : rows) {
            if (!notifications.preferences(row.userId()).riskChangeEnabled() || !significant(row)) continue;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("watchlistId", row.id().toString());
            data.put("url", row.canonicalUrl());
            data.put("urlTargetId", row.urlTargetId().toString());
            data.put("oldScore", row.previousScore());
            data.put("newScore", row.score());
            data.put("oldLevel", row.previousLevel());
            data.put("newLevel", row.level());
            data.put("reason", label);
            notifier.create(row.userId(), "WATCHLIST_RISK_CHANGED", title(row), body(row), data, true);
            created++;
        }
        return created;
    }

    private boolean significant(Row row) {
        if (row.score() == null) return false;
        if (row.previousScore() == null || row.previousLevel() == null || row.level() == null) return true;
        if (!row.level().equals(row.previousLevel())) return true;
        return row.score().subtract(row.previousScore()).abs().compareTo(minScoreDelta) >= 0;
    }

    private static String title(Row row) {
        return "Rủi ro thay đổi trên " + row.host();
    }

    private static String body(Row row) {
        String score = number(row.score());
        String level = row.level() == null ? "" : " (" + row.level() + ")";
        if (row.previousScore() == null) {
            return row.displayUrl() + " được đánh giá " + score + " điểm" + level + ".";
        }
        if (row.previousLevel() != null && row.level() != null && !row.level().equals(row.previousLevel())) {
            return "Mức rủi ro của " + row.displayUrl() + " đổi từ " + row.previousLevel() + " sang "
                + row.level() + " (" + score + " điểm).";
        }
        String direction = row.score().compareTo(row.previousScore()) >= 0 ? "tăng" : "giảm";
        return row.displayUrl() + " " + direction + " từ " + number(row.previousScore()) + " lên " + score + " điểm" + level + ".";
    }

    private static String number(BigDecimal value) {
        if (value == null) return "—";
        return value.stripTrailingZeros().toPlainString();
    }
}
