package com.baotin.notification.domain;

import java.net.IDN;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Canonical URL identity used by the watchlist. The Threat-Service owns scan
 * targets; matching reputation events by canonical hash keeps the watchlist
 * correct even before/without a target-id bridge.
 */
public final class UrlCanonicalizer {
    public record CanonicalUrl(String value, String host, String hash, UUID targetId) {}

    private static final Set<String> TRACKING = Set.of(
        "gclid", "fbclid", "msclkid", "yclid", "mc_cid", "mc_eid", "igshid", "_ga"
    );

    private UrlCanonicalizer() {}

    public static CanonicalUrl canonicalize(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("URL is required");
        String input = raw.trim();
        if (input.length() > 4096) throw new IllegalArgumentException("URL is too long");
        if (!input.contains("://")) input = "https://" + input;

        URI uri;
        try {
            uri = URI.create(input);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL is not valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http and https URLs are supported");
        }
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("URL must not contain credentials");

        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("URL must contain a host");
        host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        if (host.length() > 253 || host.contains("..") || host.startsWith(".") || host.endsWith(".")) {
            throw new IllegalArgumentException("URL host is not valid");
        }

        int port = uri.getPort();
        if (port == 0 || port > 65535) throw new IllegalArgumentException("URL port is not valid");
        boolean defaultPort = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);

        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        path = path.replaceAll("/{2,}", "/");
        if (path.length() > 2048) throw new IllegalArgumentException("URL path is too long");

        String query = canonicalQuery(uri.getRawQuery());
        String canonical = scheme + "://" + host + (port > 0 && !defaultPort ? ":" + port : "") + path
            + (query.isEmpty() ? "" : "?" + query);

        byte[] digest = sha256(canonical);
        String hash = HexFormat.of().formatHex(digest);
        return new CanonicalUrl(canonical, host, hash, targetId(digest));
    }

    private static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return "";
        List<String> pairs = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            if (part.isBlank()) continue;
            int equals = part.indexOf('=');
            String rawKey = equals < 0 ? part : part.substring(0, equals);
            String rawValue = equals < 0 ? "" : part.substring(equals + 1);
            String key = decode(rawKey);
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.startsWith("utm_") || TRACKING.contains(lower)) continue;
            pairs.add(encode(key) + "=" + encode(decode(rawValue)));
        }
        pairs.sort(Comparator.naturalOrder());
        return String.join("&", pairs);
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL query is not valid");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static UUID targetId(byte[] digest) {
        byte[] bytes = java.util.Arrays.copyOf(digest, 16);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) most = (most << 8) | (bytes[i] & 0xffL);
        for (int i = 8; i < 16; i++) least = (least << 8) | (bytes[i] & 0xffL);
        return new UUID(most, least);
    }
}
