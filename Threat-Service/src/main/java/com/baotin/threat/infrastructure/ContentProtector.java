package com.baotin.threat.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ContentProtector {
    private final byte[] hashKey, encryptionKey;
    private final boolean retainRaw;
    private final SecureRandom random = new SecureRandom();
    public ContentProtector(@Value("${scan.hash-key}") String hashKey, @Value("${scan.content-key:}") String contentKey,
                            @Value("${scan.retain-raw-text:false}") boolean retainRaw) {
        try {
            this.hashKey = Base64.getDecoder().decode(hashKey);
            this.encryptionKey = contentKey.isBlank() ? new byte[0] : Base64.getDecoder().decode(contentKey);
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Threat keys must be base64"); }
        if (this.hashKey.length < 32 || (retainRaw && this.encryptionKey.length != 32)) throw new IllegalArgumentException("Strong hash key and optional AES-256 content key required");
        this.retainRaw = retainRaw;
    }
    public String hash(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Hash unavailable"); }
    }
    public String urlIdentity(String canonical) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("Hash unavailable"); }
    }
    public byte[] encrypt(String text, UUID scanId) {
        if (!retainRaw) return null;
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(scanId.toString().getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            byte[] result = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
            return result;
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Content encryption unavailable"); }
    }
}
