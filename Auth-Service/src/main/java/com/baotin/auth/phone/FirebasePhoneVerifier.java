package com.baotin.auth.phone;

import com.baotin.auth.api.ApiFailure;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.*;
import com.google.firebase.auth.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="auth.firebase.enabled",havingValue="true")
public class FirebasePhoneVerifier implements PhoneProofVerifier,AutoCloseable {
    private final FirebaseApp app;private final FirebaseAuth auth;private final PhonePolicy policy;
    public FirebasePhoneVerifier(PhonePolicy policy,FirebaseAuth auth){this.policy=policy;this.auth=auth;this.app=null;}
    @org.springframework.beans.factory.annotation.Autowired
    public FirebasePhoneVerifier(PhonePolicy policy,@Value("${auth.firebase.credentials-path:}") String credentialsPath)throws Exception{
        this.policy=policy;
        if(System.getenv("FIREBASE_AUTH_EMULATOR_HOST")!=null)throw new IllegalStateException("Signed Firebase project proof required; emulator mode is not accepted");
        if(credentialsPath.isBlank())throw new IllegalStateException("Firebase service account file required for dev phone verification");
        try(var stream=Files.newInputStream(Path.of(credentialsPath))){
            var options=FirebaseOptions.builder().setProjectId(policy.project()).setCredentials(GoogleCredentials.fromStream(stream)).setConnectTimeout(3000).setReadTimeout(5000).build();
            app=FirebaseApp.initializeApp(options,"baotin-phone-"+UUID.randomUUID());auth=FirebaseAuth.getInstance(app);
        }
    }
    @Override @SuppressWarnings("unchecked") public Proof verify(String token){
        try{
            // true rejects revoked tokens and disabled users; no emulator bypass.
            var decoded=auth.verifyIdToken(token,true);var claims=decoded.getClaims();
            Map<String,Object> firebase=(Map<String,Object>)claims.get("firebase");
            Object time=claims.get("auth_time");
            if(firebase==null||!(time instanceof Number))throw ApiFailure.unauthorized();
            return new Proof(policy.project(),decoded.getUid(),(String)claims.get("phone_number"),(String)firebase.get("sign_in_provider"),((Number)time).longValue());
        }catch(Exception e){throw new ApiFailure(401,"PHONE_PROOF_INVALID","Firebase phone proof could not be verified");}
    }
    @Override public void close(){if(app!=null)app.delete();}
}
