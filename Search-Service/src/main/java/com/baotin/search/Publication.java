package com.baotin.search;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Only explicitly public owner snapshots enter the anonymous index. */
record Publication(String title,String display,String status,String lookupHash,Map<String,Object> metadata,String content) {
    static Publication host(String value) {
        if(!Projection.publicHost(value)) throw invalid();
        return new Publication(value,value,"ACTIVE",null,Map.of(),null);
    }
    static Publication parse(JsonNode node,String type,UUID reference) {
        String visibility=Projection.text(node,"visibility");
        if(visibility.equals("PRIVATE")) return new Publication(null,null,"REMOVED",null,Map.of(),null);
        if(!visibility.equals("PUBLIC") || !Projection.text(node,"documentType").equals(type)
                || !UUID.fromString(Projection.text(node,"referenceId")).equals(reference)) throw invalid();
        String status=Projection.text(node,"status");
        if(status.equals("REMOVED")) return new Publication(null,null,status,null,Map.of(),null);
        if(!status.equals("ACTIVE")) throw invalid();
        String title=bounded(node,"title",253),display=bounded(node,"displayValue",253);
        String content=node.path("content").isMissingNode() ? null : bounded(node,"content",10000);
        if(content!=null) {
            if(!type.equals("OTHER") || !SensitiveText.redact(content).equals(content)
                || !title.equals(prefix(content,200)) || !display.equals(prefix(content,253)))throw invalid();
        } else validateDisplay(type,title,display);
        String hash=node.path("lookupHash").isMissingNode() ? null : Projection.text(node,"lookupHash");
        if(Set.of("PHONE","BANK_ACCOUNT","EMAIL").contains(type)) {
            if(hash==null || hash.length()!=64 || !hash.chars().allMatch(c->c>='0'&&c<='9'||c>='a'&&c<='f')) throw invalid();
        } else if(hash!=null) throw invalid();
        return new Publication(title,display,status,hash,metadata(node.path("metadata")),content);
    }
    String searchText() {
        if(title==null) return "";
        return SearchQuery.normalize(content!=null?content:title.equals(display) ? title : title+" "+display);
    }
    private static String prefix(String value,int max) {
        return (value.codePointCount(0,value.length())<=max?value:value.substring(0,value.offsetByCodePoints(0,max))).strip();
    }
    private static void validateDisplay(String type,String title,String display) {
        switch(type) {
            case "URL" -> { if(!title.equals(display) || !Projection.publicHost(display)) throw invalid(); }
            case "PHONE" -> { if(!title.equals("Điện thoại") || !maskedDigits(display)) throw invalid(); }
            case "BANK_ACCOUNT" -> { if(!title.equals("Tài khoản ngân hàng") || !maskedDigits(display)) throw invalid(); }
            case "EMAIL" -> { if(!title.equals("Email") || !maskedEmail(display)) throw invalid(); }
            case "CAMPAIGN" -> { if(title.length()>200 || sensitive(title) || sensitive(display)) throw invalid(); }
            case "OTHER" -> { if(!title.equals(display) || !fingerprintTitle(title)) throw invalid(); }
            default -> throw invalid();
        }
    }
    private static boolean maskedDigits(String value) {
        int digits=0;boolean star=false;
        for(char c:value.toCharArray()) {
            if(c>='0'&&c<='9') digits++;
            else if(c=='*') star=true;
            else if(!Character.isWhitespace(c) && c!='-' && c!='+') return false;
        }
        return star && digits==4;
    }
    private static boolean maskedEmail(String value) {
        int at=value.indexOf('@');if(at<3 || at!=value.lastIndexOf('@') || !Projection.publicHost(value.substring(at+1))) return false;
        String local=value.substring(0,at);int letters=0,stars=0;
        for(char c:local.toCharArray()) { if(c=='*') stars++;else if(Character.isLetterOrDigit(c)) letters++;else return false; }
        return letters<=1 && stars>=3;
    }
    private static boolean fingerprintTitle(String value) {
        String prefix=value.startsWith("Mẫu tin nhắn #")?"Mẫu tin nhắn #":"Đối tượng #";
        if(!value.startsWith(prefix)) return false;
        String suffix=value.substring(prefix.length());
        return suffix.length()==12 && suffix.chars().allMatch(c->c>='0'&&c<='9'||c>='a'&&c<='f');
    }
    private static boolean sensitive(String value) {
        int run=0;
        for(char c:value.toCharArray()) { if(c=='@') return true;run=c>='0'&&c<='9'?run+1:0;if(run>=6)return true; }
        return false;
    }
    private static String bounded(JsonNode node,String field,int max) {
        String value=Projection.text(node,field).strip();
        if(value.isEmpty() || value.length()>max || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }
    private static Map<String,Object> metadata(JsonNode node) {
        if(!node.isObject() || node.size()>4) throw invalid();
        Map<String,Object> result=new LinkedHashMap<>();
        for(var entry:node.properties()) {
            String value=Projection.text(node,entry.getKey());
            switch(entry.getKey()) {
                case "campaignId" -> value=UUID.fromString(value).toString();
                case "bankCode" -> { if(value.isEmpty() || value.length()>20 || !value.chars().allMatch(c->c>='A'&&c<='Z')) throw invalid(); }
                case "contentWarning" -> { if(!Set.of("SUSPICIOUS","BENIGN","UNKNOWN").contains(value))throw invalid(); }
                case "referenceType" -> { if(!SearchTypes.ALL.contains(value) && !value.equals("MESSAGE_TEMPLATE")) throw invalid(); }
                default -> throw invalid();
            }
            result.put(entry.getKey(),value);
        }
        return Map.copyOf(result);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid public Search snapshot"); }
}
