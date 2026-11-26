package com.baotin.auth.security;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Standard JCA algorithms; secrets never appear in toString or logs. */
public final class AuthCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private AuthCrypto() {}
    public static String token() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static byte[] randomBytes(int size) { byte[] out = new byte[size]; RANDOM.nextBytes(out); return out; }
    public static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Hash unavailable", e); }
    }
    public static byte[] encrypt(byte[] key, byte[] plaintext) {
        return encrypt(key,plaintext,null);
    }
    public static byte[] encrypt(byte[] key, byte[] plaintext,byte[] aad) {
        try {
            byte[] nonce = randomBytes(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128, nonce));
            if(aad!=null)cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array();
        } catch (Exception e) { throw new IllegalStateException("Encryption failed", e); }
    }
    public static byte[] decrypt(byte[] key, byte[] value) {
        return decrypt(key,value,null);
    }
    public static byte[] decrypt(byte[] key, byte[] value,byte[] aad) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(value); byte[] nonce = new byte[12]; buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            if(aad!=null)cipher.updateAAD(aad);
            return cipher.doFinal(encrypted);
        } catch (Exception e) { throw new IllegalStateException("Decryption failed", e); }
    }
    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder(); int accumulator=0, bits=0;
        for(byte value:bytes) { accumulator=(accumulator<<8)|(value&255); bits+=8;
            while(bits>=5) {bits-=5; out.append(BASE32[(accumulator>>>bits)&31]);} }
        if(bits>0) out.append(BASE32[(accumulator<<(5-bits))&31]);
        return out.toString();
    }
    public static String totp(byte[] secret,long step) {
        try {
            Mac mac=Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(secret,"HmacSHA1"));
            byte[] digest=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array()); int offset=digest[digest.length-1]&15;
            int number=ByteBuffer.wrap(digest,offset,4).getInt()&0x7fffffff;
            return String.format(java.util.Locale.ROOT,"%06d",number%1_000_000);
        } catch(Exception e) {throw new IllegalStateException("TOTP unavailable",e);}
    }
    public static long matchedStep(byte[] secret,String code,long step,long lastUsed) {
        if(code==null || !code.matches("[0-9]{6}")) return -1;
        for(long candidate=step-1;candidate<=step+1;candidate++)
            if(candidate>lastUsed && MessageDigest.isEqual(totp(secret,candidate).getBytes(StandardCharsets.US_ASCII),code.getBytes(StandardCharsets.US_ASCII))) return candidate;
        return -1;
    }
}
