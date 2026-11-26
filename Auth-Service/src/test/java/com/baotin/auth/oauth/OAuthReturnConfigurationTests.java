package com.baotin.auth.oauth;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class OAuthReturnConfigurationTests {
    private OAuthService service(String key,String value) {
        return new OAuthService(null,null,null,null,null,new MockEnvironment().withProperty(key,value),null);
    }
    @Test void webTargetsMustBeHttpsHierarchicalAndCredentialFree() {
        for(String target:List.of("http://client.example/auth/callback","baotin://auth/callback",
                "https://user:secret@client.example/auth/callback","https://*.example/callback",
                "https://client.example/callback?code=sensitive","https://client.example/callback#token",
                "https:opaque-callback","//client.example/callback","not a uri")) {
            var failure=assertThrows(IllegalArgumentException.class,()->service("auth.oauth.web-return-uris",target),target);
            assertFalse(failure.getMessage().contains("sensitive"));
            assertFalse(failure.getMessage().contains("secret"));
        }
    }
    @Test void nativeTargetsRejectUnsafeSchemesAndAmbiguousAuthority() {
        for(String target:List.of("http://client.example/callback","javascript:alert(1)","data:text/plain,test",
                "file:///tmp/callback","ftp://client.example/callback","baotin:opaque",
                "baotin://user@auth/callback","baotin://auth/callback?code=x","baotin://auth/callback#ticket")) {
            assertThrows(IllegalArgumentException.class,()->service("auth.oauth.native-return-uris",target),target);
        }
    }
    @Test void exactHttpsAndExpoNativeTargetsRemainSupported() {
        for(String target:List.of("https://client.example/auth/callback","https://localhost:9444/auth/callback",
                "https://client.example/a,https://second.example/b")) {
            assertDoesNotThrow(()->service("auth.oauth.web-return-uris",target));
        }
        for(String target:List.of("baotin://auth/callback","exp+baotin://auth/callback",
                "https://client.example/native/callback")) {
            assertDoesNotThrow(()->service("auth.oauth.native-return-uris",target));
        }
        assertDoesNotThrow(()->service("auth.oauth.web-return-uris","")); // Explicitly disabled allowlist.
    }
}
