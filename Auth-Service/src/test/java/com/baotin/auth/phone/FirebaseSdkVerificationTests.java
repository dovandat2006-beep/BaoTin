package com.baotin.auth.phone;

import com.google.api.client.http.*;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.auth.oauth2.*;
import com.google.firebase.*;
import com.google.firebase.auth.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import com.baotin.auth.api.ApiFailure;
import static org.junit.jupiter.api.Assertions.*;

class FirebaseSdkVerificationTests {
    @Test void realAdminSdkChecksSignatureProjectAndRevocationWithHttpTransportFixture()throws Exception{
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();Instant now=Instant.now();
        var certificate=new JcaX509v3CertificateBuilder(new X500Name("CN=Firebase test"),BigInteger.ONE,Date.from(now.minusSeconds(60)),Date.from(now.plusSeconds(3600)),new X500Name("CN=Firebase test"),pair.getPublic()).build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate()));
        String pem="-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(certificate.getEncoded())+"\n-----END CERTIFICATE-----\n";
        var disabled=new AtomicBoolean(false);var revoked=new AtomicLong(0);var lookups=new AtomicInteger();var mapper=JsonMapper.builder().build();
        var transport=new HttpTransport(){@Override protected LowLevelHttpRequest buildRequest(String method,String url){
            return new MockLowLevelHttpRequest(url){@Override public LowLevelHttpResponse execute(){
                String response;
                if(url.contains("/metadata/x509/"))response=mapper.writeValueAsString(Map.of("firebase-test-key",pem));
                else if(url.contains("accounts:lookup")){lookups.incrementAndGet();response=mapper.writeValueAsString(Map.of("users",List.of(Map.of("localId","firebase-user","disabled",disabled.get(),"validSince",Long.toString(revoked.get())))));}
                else throw new IllegalStateException("Unexpected Firebase HTTP request");
                return new MockLowLevelHttpResponse().setStatusCode(200).setContentType("application/json").setContent(response).addHeader("Cache-Control","max-age=60");
            }};
        }};
        var options=FirebaseOptions.builder().setProjectId("phone-dev").setCredentials(GoogleCredentials.create(new AccessToken("test-only-access",Date.from(now.plusSeconds(3600))))).setHttpTransport(transport).build();
        var app=FirebaseApp.initializeApp(options,"signed-firebase-test-"+UUID.randomUUID());
        try{
            var env=new MockEnvironment().withProperty("auth.firebase.enabled","true").withProperty("auth.firebase.project-id","phone-dev").withProperty("auth.firebase.test-phones","+84900000001");env.setActiveProfiles("test");
            var adapter=new FirebasePhoneVerifier(new PhonePolicy(env),FirebaseAuth.getInstance(app));
            String good=token(pair,"phone-dev",now);assertEquals("firebase-user",adapter.verify(good).uid());assertTrue(lookups.get()>0);
            assertThrows(ApiFailure.class,()->adapter.verify(token(pair,"wrong-project",now)));
            String[] parts=good.split("\\.");parts[2]=(parts[2].charAt(0)=='A'?"B":"A")+parts[2].substring(1);assertThrows(ApiFailure.class,()->adapter.verify(String.join(".",parts)));
            disabled.set(true);assertThrows(ApiFailure.class,()->adapter.verify(good));disabled.set(false);revoked.set(now.plusSeconds(10).getEpochSecond());assertThrows(ApiFailure.class,()->adapter.verify(good));
        }finally{app.delete();}
    }
    String token(KeyPair pair,String project,Instant now)throws Exception{
        var claims=new JWTClaimsSet.Builder().issuer("https://securetoken.google.com/"+project).audience(project).subject("firebase-user").issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).claim("auth_time",now.getEpochSecond()).claim("phone_number","+84900000001").claim("firebase",Map.of("sign_in_provider","phone")).build();
        var signed=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("firebase-test-key").build(),claims);signed.sign(new RSASSASigner(pair.getPrivate()));return signed.serialize();
    }
}
