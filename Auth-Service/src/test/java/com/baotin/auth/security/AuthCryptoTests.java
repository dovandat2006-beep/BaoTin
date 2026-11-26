package com.baotin.auth.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;

class AuthCryptoTests {
    @Test void rfc6238Sha1VectorAndReplayWindow(){
        byte[] secret="12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertEquals("287082",AuthCrypto.totp(secret,1));
        assertEquals(1,AuthCrypto.matchedStep(secret,"287082",1,-1));
        assertEquals(-1,AuthCrypto.matchedStep(secret,"287082",1,1));
        assertEquals(-1,AuthCrypto.matchedStep(secret,"287082",5,-1));
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",AuthCrypto.base32(secret));
    }
    @Test void gcmTamperAndNonce(){
        byte[] key=AuthCrypto.randomBytes(32);byte[] text="secret".getBytes(StandardCharsets.UTF_8);
        byte[] first=AuthCrypto.encrypt(key,text);byte[] second=AuthCrypto.encrypt(key,text);
        assertFalse(java.util.Arrays.equals(first,second));assertArrayEquals(text,AuthCrypto.decrypt(key,first));
        first[first.length-1]^=1;assertThrows(IllegalStateException.class,()->AuthCrypto.decrypt(key,first));
    }
    @Test void opaqueTokensAreHighEntropyAndHashOnly(){String a=AuthCrypto.token();String b=AuthCrypto.token();assertNotEquals(a,b);assertEquals(43,a.length());assertEquals(64,AuthCrypto.hash(a).length());}
}
