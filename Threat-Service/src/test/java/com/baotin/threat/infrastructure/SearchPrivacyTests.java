package com.baotin.threat.infrastructure;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SearchPrivacyTests {
    @Test void onlyDnsHostsEnterSearchOutbox() {
        assertTrue(ScanStore.publicSearchHost("example.com"));
        assertFalse(ScanStore.publicSearchHost("a.".repeat(50000)+"com"));
        assertFalse(ScanStore.publicSearchHost("a".repeat(64)+".com"));
        assertTrue(ScanStore.publicSearchHost("a".repeat(63)+".com"));
        assertEquals("example.com",URI.create("https://user:secret@example.com/private?otp=1234#jwt").getHost());
        for(String value:java.util.List.of("127.0.0.1","localhost","private.internal","service.local","Example.com","example.com/private?token=x"))
            assertFalse(ScanStore.publicSearchHost(value),value);
    }
}
