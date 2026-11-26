package com.baotin.auth.security;

import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.*;
import com.nimbusds.jose.jwk.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class SigningKeys {
    private final RSAKey rsa;
    private final EncryptionKeyRing encryption;
    private final List<RSAKey> verificationKeys;
    public SigningKeys(Environment env) throws Exception {
        if(env.acceptsProfiles(Profiles.of("test"))) {
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            var pair=generator.generateKeyPair();
            rsa=new RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey(pair.getPrivate()).keyID("test-key").build();
            encryption=new EncryptionKeyRing("test","test",Map.of("test",AuthCrypto.randomBytes(32)));
            verificationKeys=List.of(rsa.toPublicJWK());
        } else {
            String path=env.getRequiredProperty("auth.private-key-path");
            String kid=env.getRequiredProperty("auth.key-id");
            if(path.isBlank() || kid.isBlank()) throw new IllegalStateException("JWT_PRIVATE_KEY_PATH and JWT_KEY_ID are required");
            String pem=Files.readString(Path.of(path)).replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
            var factory=KeyFactory.getInstance("RSA");
            var privateKey=(RSAPrivateCrtKey)factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            var publicKey=(RSAPublicKey)factory.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(),privateKey.getPublicExponent()));
            if(publicKey.getModulus().bitLength()<2048) throw new IllegalStateException("RSA key must have at least 2048 bits");
            rsa=new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(kid).build();
            encryption=EncryptionKeyRing.load(env);
            var publicKeys=new ArrayList<RSAKey>();publicKeys.add(rsa.toPublicJWK());Set<String> ids=new HashSet<>();ids.add(kid);
            String previous=env.getProperty("auth.verification-jwks-path","");
            if(!previous.isBlank())for(JWK key:JWKSet.parse(Files.readString(Path.of(previous))).getKeys()){
                if(!(key instanceof RSAKey imported)||key.isPrivate()||key.getKeyID()==null||key.getKeyID().isBlank()||!ids.add(key.getKeyID())||imported.toRSAPublicKey().getModulus().bitLength()<2048)throw new IllegalStateException("Previous JWKS must have distinct kid/public RSA keys only");
                if(imported.getAlgorithm()!=null && !com.nimbusds.jose.JWSAlgorithm.RS256.equals(imported.getAlgorithm()))throw new IllegalStateException("Previous key must use RS256");
                publicKeys.add(imported.toPublicJWK());
            }
            verificationKeys=List.copyOf(publicKeys);
        }
    }
    public RSAKey rsa() {return rsa;}
    public JWKSet publicSet(){return new JWKSet(new ArrayList<>(verificationKeys));}
    public boolean knownKid(Object value){return verificationKeys.stream().anyMatch(k->k.getKeyID().equals(value));}
    public byte[] encrypt(byte[] value) {return encryption.encrypt(value);}
    public byte[] decrypt(byte[] value) {return encryption.decrypt(value);}
}
