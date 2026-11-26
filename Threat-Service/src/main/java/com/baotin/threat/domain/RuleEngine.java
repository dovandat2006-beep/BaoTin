package com.baotin.threat.domain;

import com.baotin.threat.domain.ScanResult.Rules;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RuleEngine {
    public Rules text(String input) {
        String text = Normalizer.normalize(input, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('đ', 'd');
        List<String> signals = new ArrayList<>(); int score = 0;
        if (text.matches("(?s).*(?:\\botp\\b|mat khau|ma xac thuc|ma bao mat).*")) { score += 25; signals.add("SENSITIVE_CREDENTIAL_REQUEST"); }
        if (text.matches("(?s).*(?:khan cap|ngay lap tuc|sap bi khoa|khoa tai khoan|xac minh ngay).*")) { score += 20; signals.add("URGENCY_OR_ACCOUNT_THREAT"); }
        if (text.matches("(?s).*(?:trung thuong|nhan thuong|qua tang|loi nhuan|sieu loi).*")) { score += 20; signals.add("PRIZE_OR_PROFIT_PROMISE"); }
        if (text.matches("(?s).*(?:chuyen khoan|chuyen tien|dong phi|nop phi|dat coc).*")) { score += 25; signals.add("PAYMENT_REQUEST"); }
        if (text.matches("(?s).*(?:bam vao|nhan vao|truy cap).*(?:https?://|www\\.).*")) { score += 10; signals.add("LINK_ACTION_REQUEST"); }
        return new Rules(Math.min(100, score), List.copyOf(signals));
    }
    public Rules url(UrlNormalizer.Url url) {
        List<String> signals = new ArrayList<>(); int score = 0;
        String host = url.host(), value = url.input();
        if (host.startsWith("[") || host.matches("[0-9.]+") || host.matches("(?i)0x[0-9a-f]+")) { score += 25; signals.add("IP_HOST"); }
        if (host.contains("xn--")) { score += 15; signals.add("PUNYCODE_HOST"); }
        if (url.hadUserInfo()) { score += 25; signals.add("USERINFO_IN_URL"); }
        if (value.length() > 200) { score += 15; signals.add("LONG_URL"); }
        if (host.length() > 70 || host.chars().filter(c -> c == '.').count() >= 4) { score += 15; signals.add("COMPLEX_HOST"); }
        if (host.chars().filter(c -> c == '-').count() >= 3) { score += 10; signals.add("MANY_HOST_HYPHENS"); }
        if (host.chars().filter(Character::isDigit).count() > host.length() * 0.3) { score += 10; signals.add("HIGH_HOST_DIGIT_RATIO"); }
        if ((url.path() + "?" + url.query()).toLowerCase(Locale.ROOT).matches(".*(?:verify|xac-?nhan|password|otp|login|dang-?nhap).*")) { score += 10; signals.add("CREDENTIAL_PATH"); }
        return new Rules(Math.min(100, score), List.copyOf(signals));
    }
}
