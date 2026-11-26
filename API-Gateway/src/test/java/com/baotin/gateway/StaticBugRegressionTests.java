package com.baotin.gateway;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class StaticBugRegressionTests {
    @Test void longInvalidRouteIsRejectedWithoutRecursiveRegexOverflow() {
        String path="/api/v1/scans"+"/x".repeat(15000)+"!";
        var endpoint=new ServiceCatalog.Endpoint("long-path","threat",path,List.of(HttpMethod.GET),false);
        assertThrows(IllegalArgumentException.class,()->new ServiceCatalog(
            Map.of("threat",URI.create("http://127.0.0.1:8182")),List.of(endpoint)));
    }
    @Test void independentlyParsedProxyNetworksHaveContentValueSemantics() {
        Object a=((List<?>)ReflectionTestUtils.getField(new ProxyAddresses("127.0.0.1/32"),"networks")).getFirst();
        Object b=((List<?>)ReflectionTestUtils.getField(new ProxyAddresses("127.0.0.1/32"),"networks")).getFirst();
        assertEquals(a,b);
        assertEquals(a.hashCode(),b.hashCode());
        assertFalse(a.toString().contains("[B@"));
        assertTrue(new ProxyAddresses("127.0.0.1/32").trusted("127.0.0.1"));
        assertFalse(new ProxyAddresses("127.0.0.1/32").trusted("127.0.0.2"));
    }
}
