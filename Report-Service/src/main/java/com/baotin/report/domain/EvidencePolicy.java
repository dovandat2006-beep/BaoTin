package com.baotin.report.domain;

import com.baotin.report.api.ReportFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class EvidencePolicy {
    public static final int MAX_BYTES=10*1024*1024;
    private EvidencePolicy() {}
    public static String mime(byte[] bytes,String claimed) {
        if(bytes.length==0 || bytes.length>MAX_BYTES) throw new ReportFailure(413,"EVIDENCE_SIZE","Mỗi bằng chứng phải từ 1 byte đến 10 MB.");
        String actual;
        if(starts(bytes,new byte[]{(byte)0x89,80,78,71,13,10,26,10})) actual="image/png";
        else if(starts(bytes,new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff})) actual="image/jpeg";
        else if(starts(bytes,"%PDF-".getBytes(StandardCharsets.US_ASCII))) actual="application/pdf";
        else if(bytes.length>=12 && new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP")) actual="image/webp";
        else throw invalid();
        if(!actual.equals(claimed)) throw invalid();
        return actual;
    }
    private static boolean starts(byte[] data,byte[] prefix) {
        if(data.length<prefix.length) return false;
        for(int i=0;i<prefix.length;i++) if(data[i]!=prefix[i]) return false;
        return true;
    }
    public static String hash(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String filename(String name) {
        String safe=(name==null?"evidence":name).replace('\\','/');safe=safe.substring(safe.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]", "_");
        return safe.isBlank()?"evidence":safe.substring(0,Math.min(safe.length(),200));
    }
    private static ReportFailure invalid() { return new ReportFailure(415,"EVIDENCE_TYPE","Chỉ nhận JPG, PNG, WEBP hoặc PDF có MIME đúng nội dung."); }
}
