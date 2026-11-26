package com.baotin.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baotin.notification.domain.UrlCanonicalizer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UrlCanonicalizerTests {
    @Test
    void equivalentUrlsShareOneIdentity() {
        var first = UrlCanonicalizer.canonicalize("HTTPS://Example.COM:443/a//b?utm_source=x&b=2&a=1#frag");
        var second = UrlCanonicalizer.canonicalize("https://example.com/a/b?a=1&b=2");
        assertThat(first.value()).isEqualTo("https://example.com/a/b?a=1&b=2");
        assertThat(first.hash()).isEqualTo(second.hash());
        assertThat(first.targetId()).isEqualTo(second.targetId());
    }

    @Test
    void stripsTrackingParametersAndNormalizesDefaultPorts() {
        assertThat(UrlCanonicalizer.canonicalize("http://example.com:80/x?fbclid=1&keep=yes").value())
            .isEqualTo("http://example.com/x?keep=yes");
        assertThat(UrlCanonicalizer.canonicalize("example.com/x").value()).isEqualTo("https://example.com/x");
    }

    @Test
    void targetIdIsStableUuid() {
        var target = UrlCanonicalizer.canonicalize("https://example.com/x");
        assertThat(UUID.fromString(target.targetId().toString())).isEqualTo(target.targetId());
        assertThat(target.targetId().version()).isEqualTo(5);
    }

    @Test
    void rejectsUnsafeOrUnsupportedInput() {
        assertThatThrownBy(() -> UrlCanonicalizer.canonicalize("ftp://example.com/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlCanonicalizer.canonicalize("https://user:pass@example.com/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlCanonicalizer.canonicalize("https://example.com:99999/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlCanonicalizer.canonicalize("   ")).isInstanceOf(IllegalArgumentException.class);
    }
}
