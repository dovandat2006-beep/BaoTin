package com.baotin.notification.domain;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Rejects URLs whose query string looks like a secret rather than public identity. */
public final class UrlSafety {
    private static final Set<String> SECRET_KEYS = Set.of(
        "token", "access_token", "id_token", "refresh_token", "session", "sessionid", "session_id",
        "password", "passwd", "otp", "secret", "apikey", "api_key", "auth", "authorization", "code"
    );

    private UrlSafety() {}

    public static void rejectSecrets(String canonicalUrl) {
        URI uri = URI.create(canonicalUrl);
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) return;
        for (String part : query.split("&")) {
            String key = part.split("=", 2)[0];
            try {
                key = URLDecoder.decode(key, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("URL query is not valid");
            }
            if (SECRET_KEYS.contains(key)) {
                throw new IllegalArgumentException("URL must not contain credentials or secret query parameters");
            }
        }
    }
}
