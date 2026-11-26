package com.baotin.threat.infrastructure;

import com.baotin.threat.domain.ScanResult.ModelSignal;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.*;
import java.net.http.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Only calls configured service origins. Scan input never determines an outbound address. */
@Component
public class SignalClients {
    private final HttpClient http;
    private final URI modelOrigin;
    private final String modelToken;
    private final URI reputationOrigin;
    private final String reputationToken;
    private final boolean reputationServiceToken;
    private final Duration timeout;
    private final JsonMapper json;
    private final MeterRegistry metrics;
    private final Map<String, Long> modelBackoffUntil = new ConcurrentHashMap<>();

    public SignalClients(String modelUri, String modelToken, String reputationUri, String reputationToken,
                         int timeoutMs, JsonMapper json, MeterRegistry metrics) {
        this(modelUri, modelToken, reputationUri, reputationToken, "bearer", timeoutMs, json, metrics);
    }

    @Autowired
    public SignalClients(@Value("${scan.model-uri}") String modelUri, @Value("${scan.model-token:}") String modelToken,
                         @Value("${scan.reputation-uri:}") String reputationUri, @Value("${scan.reputation-token:}") String reputationToken,
                         @Value("${scan.reputation-auth-mode:bearer}") String reputationAuthMode,
                         @Value("${scan.provider-timeout-ms:800}") int timeoutMs, JsonMapper json, MeterRegistry metrics) {
        this.modelOrigin = origin(modelUri); this.modelToken = modelToken;
        this.reputationOrigin = reputationUri.isBlank() ? null : origin(reputationUri); this.reputationToken = reputationToken;
        if (!Set.of("bearer", "service-token").contains(reputationAuthMode)) throw new IllegalArgumentException("Invalid Reputation authentication mode");
        reputationServiceToken = reputationAuthMode.equals("service-token");
        if (reputationOrigin != null && reputationServiceToken && !reputationToken.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid Reputation service-key configuration");
        if (timeoutMs < 50 || timeoutMs > 3000) throw new IllegalArgumentException("Invalid provider timeout");
        timeout = Duration.ofMillis(timeoutMs);
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        this.json = json; this.metrics = metrics;
    }
    private URI origin(String value) {
        URI uri = URI.create(value);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !Set.of("", "/").contains(uri.getPath()) || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException("Expected a configured service origin");
        return uri;
    }

    public ModelSignal model(String type, String input, String correlation, long deadline) {
        if (modelToken.isBlank() || System.nanoTime() < modelBackoffUntil.getOrDefault(type, 0L) || !hasBudget(deadline)) return null;
        try {
            var request = HttpRequest.newBuilder(modelOrigin.resolve("/internal/v1/inference/" + type)).timeout(timeout)
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + modelToken).header("X-Correlation-Id", correlation)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(type.equals("text") ? "content" : "url", input)))).build();
            var response = send(request, 32768, deadline);
                if (response.statusCode() != 200) { if (response.statusCode() >= 500) modelBackoffUntil.put(type, System.nanoTime() + Duration.ofSeconds(2).toNanos()); return null; }
                ModelSignal signal = json.readValue(response.body(), ModelSignal.class);
                if (!valid(signal, type)) return null;
                return signal;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            modelBackoffUntil.put(type, System.nanoTime() + Duration.ofSeconds(2).toNanos());
            metrics.counter("baotin_provider_errors_total", "provider", "model").increment();
            return null;
        }
    }
    private boolean valid(ModelSignal model, String type) {
        if (model == null || model.modelName() == null || model.modelName().isBlank() || model.modelName().length() > 100 || model.modelVersion() == null || model.modelVersion().isBlank() || model.modelVersion().length() > 50
                || model.modelChecksum() == null || !model.modelChecksum().matches("[0-9a-f]{64}") || model.probabilities() == null || !Double.isFinite(model.latencyMs()) || model.latencyMs() < 0) return false;
        Set<String> labels = type.equals("text") ? Set.of("HAM", "SPAM") : Set.of("benign", "phishing_bank", "phishing_ecommerce", "phishing_gaming", "phishing_gov", "phishing_other");
        String benign = type.equals("text") ? "HAM" : "benign";
        if (!model.probabilities().keySet().equals(labels) || !labels.contains(model.label())) return false;
        double sum = 0, max = -1;
        for (var probability : model.probabilities().values()) {
            if (probability == null || !Double.isFinite(probability) || probability < 0 || probability > 1) return false;
            sum += probability; max = Math.max(max, probability);
        }
        return Double.isFinite(model.confidence()) && Double.isFinite(model.riskProbability()) && Math.abs(sum - 1) < 1e-4
                && Math.abs(model.confidence() - max) < 1e-4 && Math.abs(model.probabilities().get(model.label()) - max) < 1e-4
                && Math.abs(model.riskProbability() - (1 - model.probabilities().get(benign))) < 1e-4;
    }

    public record Reputation(Double score, boolean unavailable) {}
    public Reputation reputation(UUID targetId, String correlation, long deadline) {
        if (reputationOrigin == null || targetId == null) return new Reputation(null, false);
        if (!hasBudget(deadline)) return new Reputation(null, true);
        try {
            var request = HttpRequest.newBuilder(reputationOrigin.resolve("/internal/v1/reputation/targets/" + targetId)).timeout(timeout)
                    .header(reputationServiceToken ? "X-Service-Token" : "Authorization", reputationServiceToken ? reputationToken : "Bearer " + reputationToken)
                    .header("X-Correlation-Id", correlation).GET().build();
            var response = send(request, 4096, deadline);
                if (response.statusCode() == 404) return new Reputation(null, false);
                if (response.statusCode() != 200) return new Reputation(null, true);
                var value = json.readTree(response.body()); double score = value.path("finalScore").asDouble(Double.NaN);
                if (!targetId.toString().equals(value.path("threatTargetId").asText()) || !Double.isFinite(score) || score < 0 || score > 100) return new Reputation(null, true);
                return new Reputation(score, false);
        } catch (Exception e) { if (e instanceof InterruptedException) Thread.currentThread().interrupt(); return new Reputation(null, true); }
    }

    private boolean hasBudget(long deadline) { return deadline - System.nanoTime() >= timeout.toNanos(); }
    private HttpResponse<byte[]> send(HttpRequest request, int maximum, long deadline) throws Exception {
        var future = http.sendAsync(request, response -> new BoundedBody(maximum));
        try { return future.get(Math.min(timeout.toNanos(), Math.max(1, deadline - System.nanoTime())), TimeUnit.NANOSECONDS); }
        catch (Exception e) { future.cancel(true); throw e; }
    }
    /** Bound both complete-response time and bytes, including chunked or stalled providers. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final CompletableFuture<byte[]> complete = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        BoundedBody(int maximum) { this.maximum = maximum; }
        @Override public CompletionStage<byte[]> getBody() { return complete; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (bytes.size() + chunk.remaining() > maximum) { subscription.cancel(); complete.completeExceptionally(new IllegalStateException("Provider response too large")); return; }
                byte[] data = new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { complete.completeExceptionally(error); }
        @Override public void onComplete() { complete.complete(bytes.toByteArray()); }
    }
}
