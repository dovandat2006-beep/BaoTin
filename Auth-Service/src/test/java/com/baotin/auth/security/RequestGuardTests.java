package com.baotin.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestGuardTests {
    MockHttpServletRequest request(String remote){var r=new MockHttpServletRequest("POST","/api/v1/auth/login");r.setRemoteAddr(remote);r.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));return r;}
    @Test void untrustedCallerCannotOverrideRateIdentityOrCorrelation()throws Exception{
        var limits=mock(AttemptLimits.class);var guard=new RequestGuard(limits,"127.0.0.1/32",30,300);var request=request("198.51.100.29");request.addHeader("X-BaoTin-Client-IP","1.2.3.4");request.addHeader("X-Correlation-Id","not-an-id");var response=new MockHttpServletResponse();
        guard.doFilter(request,response,(q,r)->{});verify(limits).check("auth-write-ip","198.51.100.29",30,300);assertDoesNotThrow(()->java.util.UUID.fromString(response.getHeader("X-Correlation-Id")));
    }
    @Test void trustedGatewayMayForwardOnlyLiteralIpAndBoundedCorrelation()throws Exception{
        var limits=mock(AttemptLimits.class);var guard=new RequestGuard(limits,"127.0.0.1/32",30,300);var request=request("127.0.0.1");String id=java.util.UUID.randomUUID().toString();request.addHeader("X-BaoTin-Client-IP","100.90.80.70");request.addHeader("X-Correlation-Id",id);var response=new MockHttpServletResponse();
        guard.doFilter(request,response,(q,r)->{});verify(limits).check("auth-write-ip","100.90.80.70",30,300);assertEquals(id,response.getHeader("X-Correlation-Id"));
        var malformed=request("127.0.0.1");malformed.addHeader("X-BaoTin-Client-IP","evil.test");var rejected=new MockHttpServletResponse();guard.doFilter(malformed,rejected,(q,r)->fail("Must not forward"));assertEquals(400,rejected.getStatus());
    }
    @Test void databaseLimitFailureNeverPassesAuthenticationRequest()throws Exception{
        var limits=mock(AttemptLimits.class);doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(limits).check(anyString(),anyString(),anyInt(),anyInt());var guard=new RequestGuard(limits,"",30,300);var response=new MockHttpServletResponse();guard.doFilter(request("127.0.0.1"),response,(q,r)->fail("Must fail closed"));assertEquals(503,response.getStatus());
    }
}
