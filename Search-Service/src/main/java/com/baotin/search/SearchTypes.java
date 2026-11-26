package com.baotin.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Versioned lookup normalization; no raw contact/account value is stored or returned. */
final class SearchTypes {
    static final Set<String> ALL=Set.of("URL","PHONE","BANK_ACCOUNT","EMAIL","CAMPAIGN","OTHER");
    private SearchTypes() {}
    static String fromTarget(String value) {
        String type="MESSAGE_TEMPLATE".equals(value) ? "OTHER" : value;
        if (!ALL.contains(type) || type.equals("CAMPAIGN")) throw new IllegalArgumentException("Invalid target type");
        return type;
    }
    static List<String> lookupHashes(String query) {
        return List.of(lookupHash("PHONE",query),lookupHash("BANK_ACCOUNT",query),lookupHash("EMAIL",query));
    }
    static String lookupHash(String type,String query) {
        String canonical=switch(type) {
            case "PHONE" -> phone(query);
            case "BANK_ACCOUNT" -> bank(query);
            case "EMAIL" -> email(query);
            default -> "";
        };
        if (canonical.isEmpty()) return "";
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((type+":"+canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 required",e); }
    }
    private static String phone(String query) {
        StringBuilder digits=new StringBuilder();
        for(char c:query.strip().toCharArray()) {
            if(c>='0' && c<='9') digits.append(c);
            else if(!Character.isWhitespace(c) && "+-().".indexOf(c)<0) return "";
        }
        String value=digits.toString();
        if(value.length()==10 && value.startsWith("0")) value="84"+value.substring(1);
        return value.length()>=8 && value.length()<=15 && value.charAt(0)!='0' ? value : "";
    }
    private static String bank(String query) {
        StringBuilder result=new StringBuilder();
        for(char c:query.strip().toUpperCase(Locale.ROOT).toCharArray()) {
            if(c>='0' && c<='9' || c>='A' && c<='Z') result.append(c);
            else if(!Character.isWhitespace(c) && c!='-') return "";
        }
        return result.length()>=6 && result.length()<=34 ? result.toString() : "";
    }
    private static String email(String query) {
        String value=query.strip().toLowerCase(Locale.ROOT);int at=value.indexOf('@');
        if(at<1 || at>64 || at!=value.lastIndexOf('@') || value.length()>254
                || value.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))) return "";
        return Projection.publicHost(value.substring(at+1)) ? value : "";
    }
}
