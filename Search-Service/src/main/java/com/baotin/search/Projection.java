package com.baotin.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class Projection {
    private final ProjectionWriter writer;
    public Projection(JdbcTemplate jdbc) { this.writer=new ProjectionWriter(jdbc); }
    @Transactional
    public void ingest(String topic,String body) {
        writer.apply(null,ProjectionChange.parse(topic,body));
    }
    static String text(JsonNode n,String key) {
        if (!n.path(key).isString()) throw new IllegalArgumentException("Missing event field");
        return n.path(key).asString();
    }
    static boolean publicHost(String host) {
        if (host == null || host.length() > 253 || !host.equals(host.toLowerCase(java.util.Locale.ROOT))) return false;
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) return false;
        for (String label : labels) {
            if (!validDnsLabel(label)) return false;
        }
        String suffix = labels[labels.length - 1];
        return suffix.length() >= 2 && suffix.chars().allMatch(c -> c >= 'a' && c <= 'z')
            && !java.util.Set.of("localhost","local","internal","test","invalid").contains(suffix);
    }
    private static boolean validDnsLabel(String label) {
        if (label.isEmpty() || label.length() > 63 || !dnsAlphaNumeric(label.charAt(0))
                || !dnsAlphaNumeric(label.charAt(label.length() - 1))) return false;
        return label.chars().allMatch(c -> dnsAlphaNumeric(c) || c == '-');
    }
    private static boolean dnsAlphaNumeric(int c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
    }
}
