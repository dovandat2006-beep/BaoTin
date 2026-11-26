package com.baotin.modelinference.infrastructure;

import ai.onnxruntime.*;
import com.baotin.modelinference.domain.Prediction;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A session owns native memory for one immutable, checksummed artifact. */
public final class OnnxModel implements AutoCloseable {
    private static final OrtEnvironment ENVIRONMENT = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final ArtifactTokenizer tokenizer;
    private final JsonNode metadata;
    private final List<String> labels;
    private final Set<Integer> benign;
    private final String checksum;

    public OnnxModel(Path root, String expectedKind, int threads) throws Exception {
        var json = JsonMapper.builder().build();
        Path directory = root.toRealPath();
        JsonNode manifest = json.readTree(Files.readString(directory.resolve("sha256.json")));
        if (!manifest.isObject() || manifest.size() < 5) throw new IOException("Artifact manifest required");
        Set<String> checked = new HashSet<>();
        for (var entry : manifest.properties()) {
            Path file = directory.resolve(entry.getKey()).normalize();
            if (!file.startsWith(directory) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(directory)) throw new IOException("Invalid artifact path");
            String actual;
            try (var input = Files.newInputStream(file)) {
                var digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[65536]; int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
                actual = HexFormat.of().formatHex(digest.digest());
            }
            if (!actual.equals(entry.getValue().asText())) throw new IOException("Artifact checksum mismatch");
            checked.add(entry.getKey());
        }
        if (!checked.containsAll(Set.of("model.onnx", "metadata.json", "labels.json", "preprocessing.json", "tokenizer-spec.json", "parity.json"))) throw new IOException("Incomplete artifact");
        try (var files = Files.walk(directory)) {
            if (files.filter(Files::isRegularFile).map(directory::relativize).map(Path::toString)
                    .anyMatch(p -> !p.equals("sha256.json") && !checked.contains(p))) throw new IOException("Unlisted artifact file");
        }
        metadata = json.readTree(Files.readString(directory.resolve("metadata.json")));
        JsonNode preprocessing = json.readTree(Files.readString(directory.resolve("preprocessing.json")));
        String kind = metadata.path("tokenizer").asText();
        int length = preprocessing.path("maxLength").asInt();
        if (!kind.equals(expectedKind) || length != (kind.equals("phobert") ? 256 : 128)
                || !preprocessing.path("normalization").asText().equals("identity")
                || !preprocessing.path("padding").asText().equals("max_length")
                || !preprocessing.path("truncation").asBoolean()
                || !metadata.path("modelName").asText().matches("[A-Za-z0-9._-]{1,100}")
                || !metadata.path("modelVersion").asText().matches("[A-Za-z0-9._-]{1,50}")) throw new IOException("Unsupported artifact contract");
        labels = new ArrayList<>(); benign = new HashSet<>();
        JsonNode mapping = json.readTree(Files.readString(directory.resolve("labels.json")));
        for (JsonNode label : mapping.path("labels")) labels.add(label.asText());
        for (JsonNode id : mapping.path("benignClassIds")) benign.add(id.asInt());
        List<String> expectedLabels = kind.equals("phobert") ? List.of("HAM", "SPAM")
                : List.of("benign", "phishing_bank", "phishing_ecommerce", "phishing_gaming", "phishing_gov", "phishing_other");
        if (!labels.equals(expectedLabels) || !benign.equals(Set.of(0))) throw new IOException("Label mapping differs from AI training source");
        // Identify the complete artifact, including external weights/tokenizer, not only the small graph file.
        checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve("sha256.json"))));
        if (kind.equals("phobert") && !checked.containsAll(Set.of("vocab.txt", "bpe.codes"))) throw new IOException("PhoBERT vocabulary required");
        tokenizer = new ArtifactTokenizer(directory, kind, length, json.readTree(Files.readString(directory.resolve("tokenizer-spec.json"))));
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(threads); options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = ENVIRONMENT.createSession(directory.resolve("model.onnx").toString(), options);
        }
        try {
            Set<String> expectedInputs = kind.equals("phobert") ? Set.of("input_ids", "attention_mask") : Set.of("input_ids", "attention_mask", "token_type_ids");
            if (!session.getInputNames().equals(expectedInputs) || !session.getOutputNames().contains("logits")) throw new IOException("Unexpected ONNX tensors");
            JsonNode parity = json.readTree(Files.readString(directory.resolve("parity.json")));
            if (!parity.isArray() || parity.size() < 6) throw new IOException("Python parity corpus required");
            for (JsonNode sample : parity) {
                var tensors = tokenizer.encode(sample.path("input").asText());
                for (var tensor : tensors.entrySet()) {
                    JsonNode reference = sample.path("inputs").path(tensor.getKey());
                    if (reference.size() != tensor.getValue().length) throw new IOException("Tokenizer shape mismatch");
                    for (int i = 0; i < tensor.getValue().length; i++) if (reference.get(i).asLong() != tensor.getValue()[i]) throw new IOException("Tokenizer parity mismatch");
                }
                try (var run = new OrtSession.RunOptions()) {
                    double[] actual = probabilities(tensors, run);
                    JsonNode expected = sample.path("probabilities");
                    if (expected.size() != actual.length) throw new IOException("Output parity shape mismatch");
                    int winner = 0;
                    for (int i = 0; i < actual.length; i++) {
                        if (Math.abs(actual[i] - expected.get(i).asDouble()) > 1e-4) throw new IOException("Model probability parity mismatch");
                        if (actual[i] > actual[winner]) winner = i;
                    }
                    if (!labels.get(winner).equals(sample.path("label").asText())) throw new IOException("Model label parity mismatch");
                }
            }
        } catch (Exception e) { session.close(); throw e; }
    }

    public Prediction predict(String input, OrtSession.RunOptions options) throws OrtException {
        long start = System.nanoTime();
        double[] probabilities = probabilities(tokenizer.encode(input), options);
        Map<String, Double> mapping = new LinkedHashMap<>();
        double risk = 0; int winner = 0;
        for (int i = 0; i < probabilities.length; i++) {
            mapping.put(labels.get(i), probabilities[i]);
            if (!benign.contains(i)) risk += probabilities[i];
            if (probabilities[i] > probabilities[winner]) winner = i;
        }
        return new Prediction(metadata.path("modelName").asText(), metadata.path("modelVersion").asText(), checksum,
                labels.get(winner), probabilities[winner], Math.clamp(risk, 0, 1), mapping, (System.nanoTime() - start) / 1e6);
    }

    private double[] probabilities(Map<String, long[]> encoded, OrtSession.RunOptions options) throws OrtException {
        Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
        try {
            for (var entry : encoded.entrySet()) inputs.put(entry.getKey(), OnnxTensor.createTensor(ENVIRONMENT, new long[][]{entry.getValue()}));
            try (var result = session.run(inputs, options)) {
                Object output = result.get("logits").orElseThrow().getValue();
                if (!(output instanceof float[][] matrix) || matrix.length != 1 || matrix[0].length != labels.size()) throw new IllegalStateException("Invalid output shape");
                float[] logits = matrix[0]; double max = Double.NEGATIVE_INFINITY;
                for (float value : logits) { if (!Float.isFinite(value)) throw new IllegalStateException("Non-finite model output"); max = Math.max(max, value); }
                double[] probabilities = new double[logits.length]; double sum = 0;
                for (int i = 0; i < logits.length; i++) { probabilities[i] = Math.exp(logits[i] - max); sum += probabilities[i]; }
                if (!(sum > 0) || !Double.isFinite(sum)) throw new IllegalStateException("Degenerate model output distribution");
                for (int i = 0; i < probabilities.length; i++) probabilities[i] /= sum;
                return probabilities;
            }
        } finally { for (var tensor : inputs.values()) tensor.close(); }
    }

    public Map<String, Object> info() { return Map.of("modelName", metadata.path("modelName").asText(), "modelVersion", metadata.path("modelVersion").asText(), "modelChecksum", checksum, "labels", labels, "status", "READY"); }
    @Override public void close() throws OrtException { session.close(); }
}
