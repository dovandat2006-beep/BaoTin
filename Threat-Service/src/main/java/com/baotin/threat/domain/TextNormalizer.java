package com.baotin.threat.domain;

import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class TextNormalizer {
    /** Only for template identity/rules, never fed to the trained model. */
    public String normalize(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").strip();
    }
    public String fingerprint(String input) {
        return normalize(input).replaceAll("(?iu)(?:https?://|www\\.)[^\\s<>]+", "<URL>")
                .replaceAll("(?iu)\\d[\\d.,]*\\s*(?:triệu|trieu|nghìn|nghin|ngàn|ngan|đồng|dong|vnd|vnđ|đ|k)(?!\\p{L})", "<AMOUNT>")
                .replaceAll("\\d[\\d.,]*", "<NUMBER>");
    }
    public String preview(String input) {
        // Persist the useful wording only after sensitive values are masked.
        return SensitiveText.redact(input);
    }
}
