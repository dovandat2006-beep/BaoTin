package com.baotin.modelinference.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Exact single-sequence PhoBERT BPE / CANINE codepoint encoding, gated by Python golden tensors.
 * BPE follows Hugging Face transformers tokenization_phobert.py (Apache-2.0; VinAI/Hugging Face).
 * No lowercasing, segmentation, accent stripping, URL normalization or unbounded token cache.
 */
public final class ArtifactTokenizer {
    // Python re \s also includes ASCII information separators U+001C..U+001F.
    private static final Pattern WORD = Pattern.compile("[^\\s\\x{1C}-\\x{1F}]+\\n?", Pattern.UNICODE_CHARACTER_CLASS);
    private record Pair(String left, String right) {}
    private final Map<String, Long> vocabulary = new HashMap<>();
    private final Map<Pair, Integer> ranks = new HashMap<>();
    private final JsonNode spec;
    private final Pattern special;
    private final Map<String, JsonNode> added = new HashMap<>();
    private final int length;
    private final String kind;

    public ArtifactTokenizer(Path directory, String kind, int length, JsonNode spec) throws IOException {
        this.kind = kind;
        this.length = length;
        this.spec = spec;
        for (JsonNode token : spec.path("addedTokens")) added.put(token.path("content").asText(), token);
        special = Pattern.compile(added.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElse("(?!)"));
        if (kind.equals("phobert")) {
            vocabulary.put("<s>", 0L); vocabulary.put("<pad>", 1L);
            vocabulary.put("</s>", 2L); vocabulary.put("<unk>", 3L);
            for (String line : Files.readAllLines(directory.resolve("vocab.txt"))) {
                String value = line.strip();
                int split = value.lastIndexOf(' ');
                if (split < 1) throw new IOException("Invalid vocabulary");
                vocabulary.put(value.substring(0, split), (long) vocabulary.size());
            }
            int rank = 0;
            for (String line : Files.readAllLines(directory.resolve("bpe.codes"))) {
                String[] parts = line.strip().split("\\s+");
                if (parts.length != 3) throw new IOException("Invalid BPE codes");
                ranks.put(new Pair(parts[0], parts[1]), rank++);
            }
        } else if (!kind.equals("canine")) throw new IOException("Unsupported tokenizer");
    }

    public Map<String, long[]> encode(String text) {
        List<Long> tokens = new ArrayList<>();
        var matcher = special.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            JsonNode token = added.get(matcher.group());
            String before = text.substring(cursor, matcher.start());
            if (token.path("lstrip").asBoolean()) before = before.replaceAll("(?U)[\\s\\x{1C}-\\x{1F}]+$", "");
            plain(before, tokens);
            tokens.add(token.path("id").asLong());
            cursor = matcher.end();
            if (token.path("rstrip").asBoolean()) {
                while (cursor < text.length() && isSpace(text.codePointAt(cursor))) cursor += Character.charCount(text.codePointAt(cursor));
            }
        }
        plain(text.substring(cursor), tokens);
        long[] ids = new long[length], mask = new long[length];
        Arrays.fill(ids, spec.path("padId").asLong());
        ids[0] = spec.path("clsId").asLong(); mask[0] = 1;
        int count = Math.min(tokens.size(), length - 2);
        for (int i = 0; i < count; i++) { ids[i + 1] = tokens.get(i); mask[i + 1] = 1; }
        ids[count + 1] = spec.path("sepId").asLong(); mask[count + 1] = 1;
        Map<String, long[]> result = new LinkedHashMap<>();
        result.put("input_ids", ids); result.put("attention_mask", mask);
        if (kind.equals("canine")) result.put("token_type_ids", new long[length]);
        return result;
    }

    private static boolean isSpace(int cp) { return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85 || (cp >= 0x1c && cp <= 0x1f); }

    private void plain(String text, List<Long> tokens) {
        if (kind.equals("canine")) { text.codePoints().forEach(cp -> tokens.add((long) cp)); return; }
        var words = WORD.matcher(text);
        while (words.find()) {
            String token = words.group();
            List<String> word = new ArrayList<>(token.codePoints().mapToObj(cp -> new String(Character.toChars(cp))).toList());
            // PhoBERT returns a single-character word unchanged, including a newline.
            if (word.size() == 1) { tokens.add(vocabulary.getOrDefault(token, 3L)); continue; }
            word.set(word.size() - 1, word.getLast() + "</w>");
            while (word.size() > 1) {
                Pair best = null; int rank = Integer.MAX_VALUE;
                for (int i = 0; i < word.size() - 1; i++) {
                    Pair pair = new Pair(word.get(i), word.get(i + 1));
                    int candidate = ranks.getOrDefault(pair, Integer.MAX_VALUE);
                    if (candidate < rank) { best = pair; rank = candidate; }
                }
                if (best == null) break;
                List<String> merged = new ArrayList<>();
                for (int i = 0; i < word.size(); i++) {
                    if (i + 1 < word.size() && word.get(i).equals(best.left()) && word.get(i + 1).equals(best.right())) {
                        merged.add(best.left() + best.right()); i++;
                    } else merged.add(word.get(i));
                }
                word = merged;
            }
            String encoded = String.join("@@ ", word);
            encoded = encoded.substring(0, encoded.length() - 4);
            for (String part : encoded.split(" ", -1)) tokens.add(vocabulary.getOrDefault(part, 3L));
        }
    }
}
