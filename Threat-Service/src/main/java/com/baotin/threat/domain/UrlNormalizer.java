package com.baotin.threat.domain;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Pure lexical handling: NEVER resolves DNS, fetches pages, follows redirects or opens URLs. */
@Component
public class UrlNormalizer {
    private static final Set<String> SECRET = Set.of("token", "access_token", "refresh_token", "id_token", "session", "sessionid", "sid", "auth", "authorization", "password", "passwd", "otp", "code", "key", "api_key", "apikey", "signature", "sig", "secret", "email", "phone");
    private static final Set<String> TRACKING = Set.of("fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid");
    private static final Pattern EXTRACT = Pattern.compile("(?iu)(?:https?://|www\\.)[^\\s<>\"'\\x{1C}-\\x{1F}]+", Pattern.UNICODE_CHARACTER_CLASS);
    public record Url(String input, String canonical, String host, String path, String query, boolean hadUserInfo) {}

    public Url parse(String input) {
        if (input == null || input.isBlank() || input.length() > 4096) throw new IllegalArgumentException("INVALID_URL");
        String value = input.strip();
        if (value.codePoints().anyMatch(cp -> Character.isISOControl(cp) || Character.isWhitespace(cp)) || value.contains("\\")) throw new IllegalArgumentException("INVALID_URL");
        if (!value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")) value = "https://" + value;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme) || uri.getRawAuthority() == null) throw new IllegalArgumentException("INVALID_URL");
            String authority = uri.getRawAuthority();
            boolean userInfo = authority.contains("@");
            String hostPort = authority.substring(authority.lastIndexOf('@') + 1);
            String host; int port = -1;
            if (hostPort.startsWith("[")) {
                int end = hostPort.indexOf(']');
                if (end < 2) throw new IllegalArgumentException("INVALID_URL");
                host = hostPort.substring(0, end + 1).toLowerCase(Locale.ROOT);
                if (hostPort.length() > end + 1) {
                    if (hostPort.charAt(end + 1) != ':') throw new IllegalArgumentException("INVALID_URL");
                    String valuePort = hostPort.substring(end + 2);
                    if (!valuePort.matches("[0-9]{1,5}")) throw new IllegalArgumentException("INVALID_URL");
                    port = Integer.parseInt(valuePort);
                }
                // URI's parser validates IPv6 syntax; no DNS/network calls.
                if (uri.getHost() == null || host.contains("%")) throw new IllegalArgumentException("INVALID_URL");
            } else {
                int colon = hostPort.lastIndexOf(':');
                String unicodeHost = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
                if (colon >= 0) {
                    String valuePort = hostPort.substring(colon + 1);
                    if (!valuePort.matches("[0-9]{1,5}")) throw new IllegalArgumentException("INVALID_URL");
                    port = Integer.parseInt(valuePort);
                }
                host = IDN.toASCII(unicodeHost, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
                if (host.isEmpty() || host.length() > 253 || host.contains("%")) throw new IllegalArgumentException("INVALID_URL");
            }
            if (port == 0 || port < -1 || port > 65535) throw new IllegalArgumentException("INVALID_URL");
            if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
            String path = uri.normalize().getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            // Keep percent-encoded reserved characters; /a%2Fb and /a/b are distinct identities.
            path = normalizeEscapes(path);
            String query = cleanQuery(uri.getRawQuery());
            String canonical = new URI(scheme + "://" + host + (port == -1 ? "" : ":" + port) + path + (query.isEmpty() ? "" : "?" + query)).normalize().toASCIIString();
            return new Url(value, canonical, host, path, query, userInfo);
        } catch (URISyntaxException | IllegalArgumentException e) { throw new IllegalArgumentException("INVALID_URL"); }
    }

    private String cleanQuery(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        List<String> retained = new ArrayList<>();
        for (String pair : raw.split("&", -1)) {
            String key = pair.split("=", 2)[0];
            String decoded = key;
            // Also remove encoded spellings of credential keys. Do not decode values or reorder duplicates.
            for (int i = 0; i < 2; i++) decoded = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            decoded = decoded.toLowerCase(Locale.ROOT).replace("-", "_");
            if (decoded.startsWith("utm_") || TRACKING.contains(decoded) || SECRET.contains(decoded) || decoded.endsWith("_token") || decoded.endsWith("_secret")) continue;
            if (!pair.isEmpty()) retained.add(normalizeEscapes(pair));
        }
        return String.join("&", retained);
    }

    private String normalizeEscapes(String value) {
        var result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '%' && i + 2 < value.length()) {
                int code = Integer.parseInt(value.substring(i + 1, i + 3), 16);
                char c = (char) code;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-._~".indexOf(c) >= 0) result.append(c);
                else result.append(String.format("%%%02X", code));
                i += 2;
            } else result.append(value.charAt(i));
        }
        return result.toString();
    }

    public List<Url> extract(String text) {
        Map<String, Url> unique = new LinkedHashMap<>();
        var matcher = EXTRACT.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            while (!candidate.isEmpty() && ".,;:!?)]}»”".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
                char last = candidate.charAt(candidate.length() - 1);
                if (last == ')' && candidate.chars().filter(c -> c == '(').count() >= candidate.chars().filter(c -> c == ')').count()) break;
                if (last == ']' && candidate.chars().filter(c -> c == '[').count() >= candidate.chars().filter(c -> c == ']').count()) break;
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            try { Url url = parse(candidate); unique.putIfAbsent(url.canonical(), url); } catch (IllegalArgumentException ignored) { /* invalid link is not fetched */ }
            if (unique.size() == 6) break; // five scans + one sentinel for the truncation warning
        }
        return List.copyOf(unique.values());
    }
}
