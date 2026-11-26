package com.baotin.threat;

import com.baotin.threat.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScanDomainTests {
    private final UrlNormalizer urls = new UrlNormalizer();
    private final RiskScorer scoring = new RiskScorer(new double[]{.7, .3}, new double[]{.5, .2, .3}, new double[]{.85, .15}, new double[]{.75, .15, .1});

    @Test void canonicalizationPreservesIdentityAndRemovesTrackingCredentials() {
        assertEquals("https://example.com/b?keep=a%2Fb&keep=2", urls.parse("HTTPS://EXAMPLE.COM:443/a/../b?utm_source=x&to%6ben=secret&keep=a%2fb&keep=2#fragment").canonical());
        assertEquals("https://example.com/", urls.parse("example.com").canonical());
        assertEquals("https://xn--v-d-rma6749a.example/", urls.parse("https://ví-dụ.example").canonical());
        assertNotEquals(urls.parse("https://example.com/a%2Fb").canonical(), urls.parse("https://example.com/a/b").canonical());
        assertNotEquals(urls.parse("https://example.com/a?a=1&a=2").canonical(), urls.parse("https://example.com/a?a=2&a=1").canonical());
        assertEquals("http://[::1]/", urls.parse("http://[::1]:80").canonical());
        assertEquals("https://example.com/", urls.parse("https://user:secret@example.com/").canonical());
        assertTrue(urls.parse("https://user:secret@example.com/").hadUserInfo());
    }
    @Test void lexicalScanningNeverNeedsDnsOrPageAccess() {
        assertEquals("http://169.254.169.254/latest/meta-data/", urls.parse("http://169.254.169.254/latest/meta-data/").canonical());
        assertTrue(new RuleEngine().url(urls.parse("http://127.0.0.1/login")).signals().contains("IP_HOST"));
        for (String value : new String[]{"file:///etc/passwd", "javascript:alert(1)", "ftp://example.com", "https://bad host/", "http://x:65536", "http://x:-1", "http://x:+80", "https://x\\@evil/", "https://x/%zz"}) assertThrows(IllegalArgumentException.class, () -> urls.parse(value));
    }
    @Test void extractsAndDeduplicatesLinksWithoutEatingBalancedParentheses() {
        var extracted = urls.extract("Xem (https://example.com/a), https://example.com/a#x và www.google.com, https://example.com/a_(b).");
        assertEquals(3, extracted.size());
        assertEquals("https://example.com/a_(b)", extracted.get(2).canonical());
        assertEquals("http://[::1]/", urls.extract("Xem (http://[::1]).").getFirst().canonical());
    }
    @Test void scoresRiskProbabilityAndRenormalizesUnavailableSignals() {
        assertEquals(7, scoring.url(.1, 0, null).score(), 1e-8);
        assertEquals(68, scoring.url(.8, 20, 80.0).score(), 1e-8);
        assertEquals(20, scoring.url(null, 20, null).score(), 1e-8);
        assertEquals(56, scoring.url(null, 20, 80.0).score(), 1e-8);
        assertEquals(85, scoring.text(1.0, 0, null).score(), 1e-8);
        assertEquals(85, scoring.text(1.0, 0, 100.0).score(), 1e-8);
        assertEquals("LOW", ScanResult.Risk.of(24.99).level());
        assertEquals("MEDIUM", ScanResult.Risk.of(25).level());
        assertEquals("HIGH", ScanResult.Risk.of(50).level());
        assertEquals("CRITICAL", ScanResult.Risk.of(75).level());
    }
    @Test void templatesNeverBecomePermanentRawTextIdentity() {
        var text = new TextNormalizer();
        assertEquals(text.fingerprint("Chuyen 20.000 vao 0123456789 de xac nhan"), text.fingerprint("Chuyen 50.000 vao 9988776655 de xac nhan"));
        assertFalse(text.preview("OTP 123456 tai khoan 0901234567").contains("123456"));
        assertTrue(new RuleEngine().text("Tài khoản sắp bị khóa, xác minh ngay mã OTP").score() > 0);
    }
}
