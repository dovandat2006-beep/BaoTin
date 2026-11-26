package com.baotin.modelinference.application;

import ai.onnxruntime.*;
import com.baotin.modelinference.domain.Prediction;
import com.baotin.modelinference.infrastructure.OnnxModel;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ModelRegistry {
    private final Map<String, OnnxModel> models = new LinkedHashMap<>();
    private final Map<String, String> states = new LinkedHashMap<>();
    private final Semaphore permits;
    private final long timeoutMs;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "onnx-deadlines"); thread.setDaemon(true); return thread;
    });
    private final MeterRegistry metrics;

    public ModelRegistry(@Value("${model.text-path:}") String textPath, @Value("${model.url-path:}") String urlPath,
                         @Value("${model.threads:2}") int threads, @Value("${model.max-concurrency:2}") int concurrency,
                         @Value("${model.timeout-ms:3000}") long timeoutMs, MeterRegistry metrics) {
        if (threads < 1 || threads > 32 || concurrency < 1 || concurrency > 32 || timeoutMs < 10 || timeoutMs > 30000) throw new IllegalArgumentException("Invalid model runtime limits");
        this.permits = new Semaphore(concurrency); this.timeoutMs = timeoutMs; this.metrics = metrics;
        load("text", textPath, "phobert", threads);
        load("url", urlPath, "canine", threads);
    }

    private void load(String type, String path, String kind, int threads) {
        if (path.isBlank()) { states.put(type, "MISSING"); return; }
        try { models.put(type, new OnnxModel(Path.of(path), kind, threads)); states.put(type, "READY"); }
        catch (Exception | LinkageError e) {
            states.put(type, "INVALID_ARTIFACT");
            // No input, artifact paths or arbitrary exception messages enter logs.
            LoggerFactory.getLogger(getClass()).error("Model {} rejected ({})", type, e.getClass().getSimpleName());
        }
    }

    public Prediction infer(String type, String input) {
        OnnxModel model = models.get(type);
        if (model == null) throw new InferenceFailure(503, "MODEL_UNAVAILABLE");
        if (!permits.tryAcquire()) throw new InferenceFailure(429, "MODEL_BUSY");
        long start = System.nanoTime();
        try (var options = new OrtSession.RunOptions()) {
            ScheduledFuture<?> deadline = deadlines.schedule(() -> {
                try { options.setTerminate(true); } catch (OrtException ignored) { /* request already ended */ }
            }, timeoutMs, TimeUnit.MILLISECONDS);
            try {
                Prediction prediction = model.predict(input, options);
                metrics.counter("baotin_model_requests_total", "type", type, "outcome", "success").increment();
                return prediction;
            } finally { deadline.cancel(false); }
        } catch (OrtException | IllegalStateException e) {
            metrics.counter("baotin_model_requests_total", "type", type, "outcome", "error").increment();
            throw new InferenceFailure(503, "MODEL_INFERENCE_FAILED");
        } finally {
            metrics.timer("baotin_model_latency_seconds", "type", type).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            permits.release();
        }
    }

    public Map<String, Object> describe() {
        Map<String, Object> response = new LinkedHashMap<>();
        for (var entry : states.entrySet()) response.put(entry.getKey(), models.containsKey(entry.getKey()) ? models.get(entry.getKey()).info() : Map.of("status", entry.getValue()));
        return response;
    }
    public boolean ready() { return models.size() == 2; }

    @PreDestroy void close() {
        deadlines.shutdownNow();
        for (var model : models.values()) try { model.close(); } catch (OrtException ignored) { /* shutdown */ }
    }

    public static final class InferenceFailure extends RuntimeException {
        public final int status;
        public InferenceFailure(int status, String code) { super(code); this.status = status; }
    }
}
