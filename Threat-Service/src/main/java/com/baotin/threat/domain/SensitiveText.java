package com.baotin.threat.domain;

import java.net.URI;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Bounded automatic masking; keep warning wording, never fetch submitted URLs. */
public final class SensitiveText {
    private SensitiveText() {}
    private static final Pattern URL=Pattern.compile("(?iuU)(?:https?://|www\\.)[^\\s<>]{1,10000}");
    private static final Pattern EMAIL=Pattern.compile("(?iuU)[\\p{L}\\p{N}._%+\\-]{1,128}@[\\p{L}\\p{N}.\\-]{1,253}");
    private static final Pattern LABELED=Pattern.compile("(?iuU)(\\b(?:otp|pin|password|passcode|token|secret|api[_ -]?key|mật khẩu|mã xác thực|mã otp|cccd|cmnd|số tài khoản|số thẻ|stk|họ tên|tên người nhận|địa chỉ|address)\\s{0,8}[:=]?\\s{0,8})([^\\s;,!?]{1,160})");
    private static final Pattern PERSONAL=Pattern.compile("(?iuU)(\\b(?:họ tên|tên người nhận|địa chỉ|address)\\s{0,8}[:=]?\\s{0,8})([^;,!?\\r\\n]{1,300})");
    private static final Pattern NUMBERS=Pattern.compile("(?<![\\p{L}\\p{N}])(?:[+]?\\d[\\d .()\\-]{4,40}\\d)(?![\\p{L}\\p{N}])");
    private static final Pattern HANDLE=Pattern.compile("(?iuU)(?<![\\p{L}\\p{N}])@[\\p{L}\\p{N}_.]{2,64}");
    private static final Pattern TOKEN=Pattern.compile("(?<![\\p{L}\\p{N}])[A-Za-z0-9_\\-]{32,}(?![\\p{L}\\p{N}])");
    public static String redact(String input) {
        if(input==null || input.length()>10000)throw new IllegalArgumentException("Text must be bounded");
        String value=Normalizer.normalize(input,Normalizer.Form.NFC);
        value=URL.matcher(value).replaceAll(match->{
            try {
                String raw=match.group();URI uri=URI.create(raw.toLowerCase(Locale.ROOT).startsWith("www.")?"https://"+raw:raw);
                String host=uri.getHost();if(host==null || host.length()>253)return "***";
                return "https://"+host.toLowerCase(Locale.ROOT)+(uri.getRawPath()!=null&&!uri.getRawPath().equals("/")&&!uri.getRawPath().isEmpty()?"/***":"");
            }catch(IllegalArgumentException ignored){return "***";}
        });
        value=EMAIL.matcher(value).replaceAll("***");
        value=PERSONAL.matcher(value).replaceAll("$1***");
        value=LABELED.matcher(value).replaceAll("$1***");
        value=NUMBERS.matcher(value).replaceAll("***");
        value=HANDLE.matcher(value).replaceAll("***");
        value=TOKEN.matcher(value).replaceAll("***");
        return value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").strip();
    }
}
