package com.baotin.auth.api;

import com.baotin.auth.application.AuthService;
import com.baotin.auth.oauth.OAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebCookieLifetimeTests {
    @Test void thirtyDayCookiesKeepTheirExistingSecurityAndNoRefreshJson() {
        var service=mock(AuthService.class);
        when(service.bindCsrf("TEST-FIXTURE-ONLY-refresh")).thenReturn("TEST-FIXTURE-ONLY-csrf");
        var controller=new WebAuthController(service,"https://fixture.example.invalid",mock(OAuthService.class));
        ResponseEntity<?> response=ReflectionTestUtils.invokeMethod(controller,"tokens",
            new AuthService.Tokens("TEST-FIXTURE-ONLY-access","TEST-FIXTURE-ONLY-refresh","Bearer",600));
        var cookies=response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertNotNull(cookies);assertEquals(2,cookies.size());
        for(String cookie:cookies){assertTrue(cookie.contains("Max-Age=2592000"));assertTrue(cookie.contains("Secure"));assertTrue(cookie.contains("SameSite=Lax"));}
        assertTrue(cookies.getFirst().contains("HttpOnly"));
        assertFalse(((java.util.Map<?,?>)response.getBody()).containsKey("refreshToken"));
    }
}
