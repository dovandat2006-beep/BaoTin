package com.baotin.threat.domain;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/** Shared target identity owner. Never fetch a submitted URL or persist raw message text. */
public record TargetIdentity(String type,String canonical,String hash) {
    public static TargetIdentity of(String type,String input) {
        if(input==null || input.isBlank() || input.length()>10000) throw new IllegalArgumentException();
        String canonical;
        if("URL".equals(type)) {
            var url=new UrlNormalizer().parse(input);
            if(url.hadUserInfo()) throw new IllegalArgumentException("Invalid URL");
            canonical=url.canonical();
        } else if("MESSAGE_TEMPLATE".equals(type)) {
            canonical=Normalizer.normalize(input,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
        } else throw new IllegalArgumentException("Invalid type");
        try { return new TargetIdentity(type,canonical,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
