package com.baotin.gateway;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProxyAddressesTests {
    @Test void onlyKnownProxyHopsCanSupplyClientAddress(){
        var policy=new ProxyAddresses("127.0.0.1/32,172.28.0.10/32");
        assertEquals("198.51.100.25",policy.client("127.0.0.1","198.51.100.25"));
        assertEquals("198.51.100.25",policy.client("127.0.0.1","1.2.3.4, 198.51.100.25"));
        assertEquals("198.51.100.25",policy.client("127.0.0.1","198.51.100.25,172.28.0.10"));
        assertEquals("198.51.100.25",policy.client("198.51.100.25","1.2.3.4"));
        assertThrows(IllegalArgumentException.class,()->policy.client("127.0.0.1","evil.test"));
    }
    @Test void cidrsAndHeaderLiteralsCannotTriggerHostResolutionOrGlobalTrust(){
        assertThrows(IllegalArgumentException.class,()->new ProxyAddresses("0.0.0.0/0"));
        assertThrows(IllegalArgumentException.class,()->new ProxyAddresses("100.64.0.0/10"));
        for(String value:java.util.List.of("localhost","abc","127.01.0.1","127.0.0.1:8080","1.2.3","fe80::1%eth0"))assertThrows(IllegalArgumentException.class,()->ProxyAddresses.literal(value));
        assertDoesNotThrow(()->ProxyAddresses.literal("::1"));
    }
}
