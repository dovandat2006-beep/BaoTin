package com.baotin.auth.application;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityValueRegressionTests {
    private IdentityStore.User user(byte[] cipher) {
        return new IdentityStore.User(UUID.fromString("00000000-0000-4000-8000-000000000001"),
            "fixture_user","fixture@example.invalid","TEST-FIXTURE-ONLY-password-hash","Fixture",
            "USER","ACTIVE",true,0,cipher,true,-1,"+999000000001",false,false);
    }
    @Test void encryptedCredentialBytesCompareByContent() {
        var a=user(new byte[]{1,2,3});var b=user(new byte[]{1,2,3});
        assertEquals(a,b);assertEquals(a.hashCode(),b.hashCode());
        assertNotEquals(a,user(new byte[]{1,2,4}));
    }
    @Test void identityDiagnosticsNeverExposeCredentialOrContactFields() {
        String text=user(new byte[]{1,2,3}).toString();
        assertFalse(text.contains("TEST-FIXTURE-ONLY-password-hash"));
        assertFalse(text.contains("fixture@example.invalid"));
        assertFalse(text.contains("+999000000001"));
        assertFalse(text.contains("[B@"));
    }
    @Test void mutableInputOrAccessorCannotAlterCredentialValueOrHash() {
        byte[] input={1,2,3};var value=user(input);int hash=value.hashCode();
        input[0]=9;byte[] exposed=value.mfaSecret();exposed[1]=9;
        assertArrayEquals(new byte[]{1,2,3},value.mfaSecret());
        assertEquals(hash,value.hashCode());
    }
}
