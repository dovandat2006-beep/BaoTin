package com.baotin.modelinference;

import ai.onnxruntime.OrtSession;
import com.baotin.modelinference.infrastructure.OnnxModel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class OnnxModelTests {
    @TempDir Path temporary;

    @Test void realNativeSessionsMatchPythonGoldenTensorsAndProbabilities() throws Exception {
        for (String type : List.of("text", "url")) {
            try (var model = new OnnxModel(Path.of("src/test/resources/fixtures", type), type.equals("text") ? "phobert" : "canine", 1);
                 var run = new OrtSession.RunOptions()) {
                var prediction = model.predict(type.equals("text") ? "Xin chào bạn" : "https://example.test", run);
                assertEquals("TEST-FIXTURE", prediction.modelVersion());
                String artifactHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("src/test/resources/fixtures", type, "sha256.json"))));
                assertEquals(artifactHash, prediction.modelChecksum(), "Identity must include external weights and preprocessing");
                assertEquals(1.0, prediction.probabilities().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-8);
                assertEquals(1.0 - prediction.probabilities().values().iterator().next(), prediction.riskProbability(), 1e-8);
                assertTrue(prediction.latencyMs() >= 0);
                var different = model.predict(type.equals("text") ? "a" : "a", run);
                assertNotEquals(prediction.probabilities(), different.probabilities(), "Fixture must actually consume input tensors");
            }
        }
    }

    @Test void rejectsModifiedWeightsBeforeExecutingOnnx() throws Exception {
        Path directory = copy("url");
        Files.write(directory.resolve("model.onnx"), new byte[]{0, 1, 2});
        assertThrows(Exception.class, () -> new OnnxModel(directory, "canine", 1));
    }
    @Test void rejectsTamperedExternalWeightsBeforeExecutingOnnx() throws Exception {
        Path directory = copy("url");
        Path external = directory.resolve("fixture.weights");
        byte[] bytes = Files.readAllBytes(external); bytes[0] ^= 1; Files.write(external, bytes);
        assertEquals("Artifact checksum mismatch", assertThrows(Exception.class, () -> new OnnxModel(directory, "canine", 1)).getMessage());
    }

    @Test void rejectsTokenizerParityDriftEvenWhenManifestIsValid() throws Exception {
        Path directory = copy("text");
        Path vocabulary = directory.resolve("vocab.txt");
        String content = Files.readString(vocabulary);
        String[] lines = content.split("\n", 3);
        Files.writeString(vocabulary, lines[1] + "\n" + lines[0] + "\n" + lines[2]);
        rehash(directory, "vocab.txt");
        // Changing the model's reference output is also caught separately from checksums.
        var json = JsonMapper.builder().build();
        var parity = json.readTree(Files.readString(directory.resolve("parity.json")));
        ((tools.jackson.databind.node.ObjectNode) parity.get(0)).put("label", "INVALID");
        Files.writeString(directory.resolve("parity.json"), json.writeValueAsString(parity));
        rehash(directory, "parity.json");
        assertThrows(Exception.class, () -> new OnnxModel(directory, "phobert", 1));
    }

    @Test void rejectsTraversalAndUnlistedFiles() throws Exception {
        Path directory = copy("url");
        Files.writeString(directory.resolve("unlisted.txt"), "unexpected");
        assertThrows(Exception.class, () -> new OnnxModel(directory, "canine", 1));
        Files.delete(directory.resolve("unlisted.txt"));
        var json = JsonMapper.builder().build();
        var manifest = (tools.jackson.databind.node.ObjectNode) json.readTree(Files.readString(directory.resolve("sha256.json")));
        manifest.put("../outside", "0".repeat(64));
        Files.writeString(directory.resolve("sha256.json"), json.writeValueAsString(manifest));
        assertThrows(Exception.class, () -> new OnnxModel(directory, "canine", 1));
    }

    private Path copy(String type) throws Exception {
        Path source = Path.of("src/test/resources/fixtures", type), destination = temporary.resolve(type);
        try (var files = Files.walk(source)) {
            for (Path path : files.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
            }
        }
        return destination;
    }
    private void rehash(Path directory, String file) throws Exception {
        var json = JsonMapper.builder().build();
        var manifest = (tools.jackson.databind.node.ObjectNode) json.readTree(Files.readString(directory.resolve("sha256.json")));
        manifest.put(file, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve(file)))));
        Files.writeString(directory.resolve("sha256.json"), json.writeValueAsString(manifest));
    }
}
