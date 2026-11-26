package com.baotin.threat.application;

import com.baotin.threat.domain.*;
import com.baotin.threat.domain.ScanResult.*;
import com.baotin.threat.infrastructure.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public class ScanService {
    private final UrlNormalizer urls;
    private final TextNormalizer texts;
    private final RuleEngine rules;
    private final RiskScorer scores;
    private final SignalClients clients;
    private final ScanStore store;
    private final ContentProtector privacy;
    private final MeterRegistry metrics;
    private final Semaphore capacity = new Semaphore(8);

    public ScanService(UrlNormalizer urls, TextNormalizer texts, RuleEngine rules, RiskScorer scores, SignalClients clients, ScanStore store, ContentProtector privacy, MeterRegistry metrics) {
        this.urls = urls; this.texts = texts; this.rules = rules; this.scores = scores; this.clients = clients; this.store = store; this.privacy = privacy; this.metrics = metrics;
    }

    public ScanResult scan(String type, String input, UUID userId, UUID correlation) {
        if (input == null || input.isBlank() || input.length() > (type.equals("TEXT") ? 10000 : 4096)) throw new IllegalArgumentException("INVALID_SCAN_INPUT");
        // Validate direct URLs before invoking any provider. Raw text goes unchanged to inference.
        UrlNormalizer.Url direct = type.equals("URL") ? urls.parse(input) : null;
        if (!capacity.tryAcquire()) throw new ScanBusy();
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(3200);
        try {
            List<String> warnings = new ArrayList<>(); List<UrlResult> analyzed = new ArrayList<>();
            ModelSignal textModel = type.equals("TEXT") ? clients.model("text", input, correlation.toString(), deadline) : null;
            if (type.equals("TEXT") && textModel == null) warnings.add("TEXT_AI_UNAVAILABLE");
            List<UrlNormalizer.Url> extracted = direct == null ? urls.extract(input) : List.of(direct);
            if (extracted.size() > 5) warnings.add("EMBEDDED_URL_LIMIT");
            for (var url : extracted.stream().limit(5).toList()) {
                ModelSignal model = clients.model("url", url.input(), correlation.toString(), deadline);
                UUID knownTarget = store.existingUrl(privacy.urlIdentity(url.canonical()));
                var reputation = clients.reputation(knownTarget, correlation.toString(), deadline);
                if (model == null && !warnings.contains("URL_AI_UNAVAILABLE")) warnings.add("URL_AI_UNAVAILABLE");
                if (reputation.unavailable() && !warnings.contains("REPUTATION_UNAVAILABLE")) warnings.add("REPUTATION_UNAVAILABLE");
                Rules lexical = rules.url(url);
                analyzed.add(new UrlResult(url.canonical(), null, model, lexical, reputation.score(), scores.url(model == null ? null : model.riskProbability(), lexical.score(), reputation.score()), model != null));
            }
            Rules lexical = direct == null ? rules.text(texts.normalize(input)) : analyzed.getFirst().rules();
            Double embedded = analyzed.isEmpty() ? null : analyzed.stream().mapToDouble(u -> u.risk().score()).max().orElse(0);
            Risk risk = direct == null ? scores.text(textModel == null ? null : textModel.riskProbability(), lexical.score(), embedded) : analyzed.getFirst().risk();
            boolean ai = (type.equals("URL") || textModel != null) && analyzed.stream().allMatch(UrlResult::aiAvailable);
            var provisional = new ScanResult(UUID.randomUUID(), type, null, direct == null ? texts.preview(input) : ScanStore.truncate(direct.canonical(), 300), risk,
                    textModel, direct == null ? null : analyzed.getFirst().model(), lexical, List.copyOf(analyzed), ai, !warnings.isEmpty(), List.copyOf(warnings), Instant.now());
            ScanResult result = store.save(provisional, userId, input, correlation);
            metrics.counter("baotin_scan_total", "type", type, "ai_available", Boolean.toString(ai)).increment();
            return result;
        } finally {
            metrics.timer("baotin_scan_latency_seconds", "type", type).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            capacity.release();
        }
    }
    public static final class ScanBusy extends RuntimeException {}
}
