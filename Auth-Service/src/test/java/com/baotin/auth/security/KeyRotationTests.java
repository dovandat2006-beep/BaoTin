package com.baotin.auth.security;

import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.time.*;
import com.nimbusds.jose.jwk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.jupiter.api.Assertions.*;

class KeyRotationTests {
    @TempDir Path temp;
    KeyPair pair()throws Exception{var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair();}
    Path privateKey(String name,KeyPair pair)throws Exception{Path p=temp.resolve(name);String pem="-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n";Files.writeString(p,pem);return p;} // gitleaks:allow - PEM is generated at runtime from a random test-only key, not a stored credential.
    MockEnvironment env(String kid,Path privateFile,String enc,Path encFile){return new MockEnvironment().withProperty("auth.private-key-path",privateFile.toString()).withProperty("auth.key-id",kid).withProperty("auth.encryption-key-id",enc).withProperty("auth.encryption-key-path",encFile.toString()).withProperty("auth.issuer","baotin-auth").withProperty("auth.audience","baotin-api");}
    @Test void restartRotationOverlapAndEncryptionRewrap()throws Exception{
        var a=pair();var b=pair();Path aFile=privateKey("a.pem",a),bFile=privateKey("b.pem",b);Path e1=temp.resolve("e1.bin"),e2=temp.resolve("e2.bin");Files.write(e1,AuthCrypto.randomBytes(32));Files.write(e2,AuthCrypto.randomBytes(32));
        var first=new SigningKeys(env("a",aFile,"e1",e1));var issuer=new JwtTokens("baotin-auth","baotin-api",first);var now=Instant.now();
        String token=issuer.issue(issuer.encoder(),UUID.randomUUID(),UUID.randomUUID(),"USER",0,false,now,now);
        byte[] encrypted=first.encrypt("stored-mfa".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var restarted=new SigningKeys(env("a",aFile,"e1",e1));assertEquals("stored-mfa",new String(restarted.decrypt(encrypted),java.nio.charset.StandardCharsets.UTF_8));assertNotNull(new JwtTokens("baotin-auth","baotin-api",restarted).decoder(Clock.systemUTC()).decode(token));
        Path jwks=temp.resolve("previous.json");Files.writeString(jwks,new JWKSet(new RSAKey.Builder((RSAPublicKey)a.getPublic()).keyID("a").build()).toString());
        var config=env("b",bFile,"e2",e2).withProperty("auth.verification-jwks-path",jwks.toString()).withProperty("auth.legacy-encryption-key-id","e1").withProperty("auth.previous-encryption-keys","e1="+e1);
        var rotated=new SigningKeys(config);assertEquals(2,rotated.publicSet().size());assertTrue(rotated.publicSet().getKeys().stream().noneMatch(JWK::isPrivate));
        assertNotNull(new JwtTokens("baotin-auth","baotin-api",rotated).decoder(Clock.systemUTC()).decode(token));assertEquals("stored-mfa",new String(rotated.decrypt(encrypted),java.nio.charset.StandardCharsets.UTF_8));
        var ring=EncryptionKeyRing.load(config);byte[] migrated=ring.rewrap(encrypted);assertTrue(ring.isCurrent(migrated));
        var retired=new SigningKeys(env("b",bFile,"e2",e2));assertEquals("stored-mfa",new String(retired.decrypt(migrated),java.nio.charset.StandardCharsets.UTF_8));assertThrows(JwtException.class,()->new JwtTokens("baotin-auth","baotin-api",retired).decoder(Clock.systemUTC()).decode(token));assertThrows(IllegalStateException.class,()->retired.decrypt(encrypted));
        byte[] legacy=AuthCrypto.encrypt(Files.readAllBytes(e1),"legacy".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals("legacy",new String(rotated.decrypt(legacy),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void ciphertextHeaderIsAuthenticatedAndPreviousJwksCannotContainPrivateKey()throws Exception{
        byte[] key=AuthCrypto.randomBytes(32);var ring=new EncryptionKeyRing("a","a",Map.of("a",key,"b",key));byte[] value=ring.encrypt("secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));value[5]='b';assertThrows(IllegalStateException.class,()->ring.decrypt(value));
        var pair=pair();Path p=privateKey("p.pem",pair),e=temp.resolve("e.bin"),j=temp.resolve("j.json");Files.write(e,key);
        Files.writeString(j,new JWKSet(new RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey(pair.getPrivate()).keyID("previous").build()).toString(false));
        assertThrows(IllegalStateException.class,()->new SigningKeys(env("current",p,"a",e).withProperty("auth.verification-jwks-path",j.toString())));
    }
}
