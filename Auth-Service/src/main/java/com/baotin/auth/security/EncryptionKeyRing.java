package com.baotin.auth.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.env.Environment;

/** AES-GCM key-id envelope, with header authenticated as AAD. */
public final class EncryptionKeyRing {
    private static final byte[] MAGIC="BK1:".getBytes(StandardCharsets.US_ASCII);
    private final String active,legacy;private final Map<String,byte[]> keys;
    public EncryptionKeyRing(String active,String legacy,Map<String,byte[]> keys){
        if(!keys.containsKey(active)||!keys.containsKey(legacy)||keys.keySet().stream().anyMatch(k->!k.matches("[A-Za-z0-9_-]{1,64}"))||keys.values().stream().anyMatch(k->k.length!=32))throw new IllegalArgumentException("Invalid encryption key ring");
        this.active=active;this.legacy=legacy;var copy=new HashMap<String,byte[]>();keys.forEach((k,v)->copy.put(k,v.clone()));this.keys=Collections.unmodifiableMap(copy);
    }
    public static EncryptionKeyRing load(Environment env)throws Exception{
        String active=env.getProperty("auth.encryption-key-id","v1"),legacy=env.getProperty("auth.legacy-encryption-key-id",active);
        var keys=new HashMap<String,byte[]>();keys.put(active,Files.readAllBytes(Path.of(env.getRequiredProperty("auth.encryption-key-path"))));
        String previous=env.getProperty("auth.previous-encryption-keys","");
        if(!previous.isBlank())for(String entry:previous.split(",")){
            String[] pair=entry.trim().split("=",2);if(pair.length!=2||keys.putIfAbsent(pair[0],Files.readAllBytes(Path.of(pair[1])))!=null)throw new IllegalArgumentException("Duplicate/malformed previous encryption key");
        }
        return new EncryptionKeyRing(active,legacy,keys);
    }
    private byte[] header(String id){byte[] name=id.getBytes(StandardCharsets.US_ASCII);return ByteBuffer.allocate(5+name.length).put(MAGIC).put((byte)name.length).put(name).array();}
    public byte[] encrypt(byte[] plaintext){byte[] header=header(active),body=AuthCrypto.encrypt(keys.get(active),plaintext,header);return ByteBuffer.allocate(header.length+body.length).put(header).put(body).array();}
    public byte[] decrypt(byte[] value){
        if(value==null||value.length<28)throw new IllegalStateException("Invalid encrypted value");
        if(!Arrays.equals(Arrays.copyOf(value,4),MAGIC))return AuthCrypto.decrypt(keys.get(legacy),value);
        int length=value[4]&255;
        if(length<1||length>64||value.length<5+length+28)throw new IllegalStateException("Invalid encrypted envelope");
        String id=new String(value,5,length,StandardCharsets.US_ASCII);byte[] key=keys.get(id);
        if(key==null)throw new IllegalStateException("Encryption key id not configured");
        return AuthCrypto.decrypt(key,Arrays.copyOfRange(value,5+length,value.length),Arrays.copyOf(value,5+length));
    }
    public byte[] rewrap(byte[] value){return encrypt(decrypt(value));}
    public boolean isCurrent(byte[] value){byte[] header=header(active);return value!=null&&value.length>header.length&&Arrays.equals(Arrays.copyOf(value,header.length),header);}
}
