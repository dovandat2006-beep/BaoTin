package com.baotin.modelinference;

import com.baotin.modelinference.infrastructure.ArtifactTokenizer;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class TokenizerParityTests {
    @Test void everyTensorExactlyMatchesHuggingFaceIncludingUnicodeAndSpecialTokens() throws Exception {
        var json = JsonMapper.builder().build();
        for (String type : List.of("text", "url")) {
            Path directory = Path.of("src/test/resources/fixtures", type);
            var tokenizer = new ArtifactTokenizer(directory, type.equals("text") ? "phobert" : "canine", type.equals("text") ? 256 : 128,
                    json.readTree(Files.readString(directory.resolve("tokenizer-spec.json"))));
            var corpus = json.readTree(Files.readString(directory.resolve("parity.json")));
            for (int sample = 0; sample < corpus.size(); sample++) {
                var reference = corpus.get(sample);
                var actual = tokenizer.encode(reference.path("input").asText());
                for (var entry : actual.entrySet()) {
                    var expected = reference.path("inputs").path(entry.getKey());
                    for (int position = 0; position < entry.getValue().length; position++)
                        assertEquals(expected.get(position).asLong(), entry.getValue()[position], type + " sample " + sample + " tensor " + entry.getKey() + " position " + position);
                }
            }
        }
    }
}
