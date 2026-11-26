package com.baotin.threat.api;

import com.baotin.threat.infrastructure.ContentProtector;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

@Component
public class RateLimits {
    private final JdbcTemplate jdbc;
    private final ContentProtector privacy;
    private final List<IpAddressMatcher> gateways;
    private final int guestLimit, userLimit;
    public RateLimits(JdbcTemplate jdbc, ContentProtector privacy, @Value("${scan.trusted-gateway-cidrs:}") String gateways,
                      @Value("${scan.guest-limit:10}") int guestLimit, @Value("${scan.user-limit:60}") int userLimit) {
        this.jdbc = jdbc; this.privacy = privacy;
        this.gateways = Arrays.stream(gateways.split(",")).map(String::strip).filter(v -> !v.isEmpty()).map(IpAddressMatcher::new).toList();
        if (guestLimit < 1 || userLimit < guestLimit) throw new IllegalArgumentException("Invalid scan rate limits");
        this.guestLimit = guestLimit; this.userLimit = userLimit;
    }
    public void check(UUID user, HttpServletRequest request) {
        String identity;
        if (user != null) identity = "user:" + user;
        else {
            String remote = request.getRemoteAddr(), verified = request.getHeader("X-BaoTin-Client-IP");
            String client = gateways.stream().anyMatch(g -> g.matches(remote)) && verified != null && verified.matches("[0-9A-Fa-f:.]{3,64}") ? verified : remote;
            identity = "guest:" + client;
        }
        int count = jdbc.queryForObject("INSERT INTO threat.rate_limits(bucket_key,window_id,attempts) VALUES (?,?,1) ON CONFLICT(bucket_key,window_id) DO UPDATE SET attempts=LEAST(threat.rate_limits.attempts+1,1000000) RETURNING attempts",
                Integer.class, privacy.hash("rate:" + identity), System.currentTimeMillis() / 300000);
        if (count > (user == null ? guestLimit : userLimit)) throw new Exceeded();
    }
    public static final class Exceeded extends RuntimeException {}
}
