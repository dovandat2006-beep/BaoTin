package com.baotin.auth.phone;

import java.time.Instant;
import java.util.Map;
import com.google.firebase.auth.*;
import com.baotin.auth.api.ApiFailure;
import org.springframework.mock.env.MockEnvironment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirebaseAdapterTests {
    @Test void sdkRevocationCheckIsMandatoryAndUsesAuthTimeRatherThanRefreshedIat()throws Exception{
        var env=new MockEnvironment().withProperty("auth.firebase.enabled","true").withProperty("auth.firebase.project-id","phone-dev").withProperty("auth.firebase.test-phones","+84900000001");env.setActiveProfiles("test");
        var sdk=mock(FirebaseAuth.class);var token=mock(FirebaseToken.class);long time=Instant.now().minusSeconds(10).getEpochSecond();
        when(token.getUid()).thenReturn("user-id");when(token.getClaims()).thenReturn(Map.of("auth_time",time,"iat",Instant.now().getEpochSecond(),"phone_number","+84900000001","firebase",Map.of("sign_in_provider","phone")));
        when(sdk.verifyIdToken("signed",true)).thenReturn(token);
        var adapter=new FirebasePhoneVerifier(new PhonePolicy(env),sdk);var proof=adapter.verify("signed");
        assertEquals(time,proof.authTime());assertEquals("phone",proof.provider());verify(sdk).verifyIdToken("signed",true);verify(sdk,never()).verifyIdToken("signed");
        when(sdk.verifyIdToken("revoked",true)).thenThrow(new IllegalArgumentException("revoked"));assertThrows(ApiFailure.class,()->adapter.verify("revoked"));adapter.close();
    }
}
