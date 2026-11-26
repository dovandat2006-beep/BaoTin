package com.baotin.auth.security;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class ProxyNetworkValueTests {
    @Test void proxyNetworkValueSemanticsDoNotChangeTrustBoundary() {
        Object a=((List<?>)ReflectionTestUtils.getField(new ProxyAddresses("::1/128"),"networks")).getFirst();
        Object b=((List<?>)ReflectionTestUtils.getField(new ProxyAddresses("::1/128"),"networks")).getFirst();
        assertEquals(a,b);assertEquals(a.hashCode(),b.hashCode());
        assertFalse(a.toString().contains("[B@"));
        assertTrue(new ProxyAddresses("::1/128").trusted("::1"));
        assertFalse(new ProxyAddresses("::1/128").trusted("::2"));
    }
}
