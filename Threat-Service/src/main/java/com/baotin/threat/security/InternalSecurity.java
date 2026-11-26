package com.baotin.threat.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class InternalSecurity {
    @Bean @org.springframework.core.annotation.Order(0)
    SecurityFilterChain internalApiSecurityFilterChain(HttpSecurity http,@Value("${threat.report-service-token:}") String secret,
        @Value("${threat.notification-service-token:}") String notificationSecret) throws Exception {
        if(secret.length()<32) throw new IllegalArgumentException("REPORT_THREAT_SERVICE_TOKEN must contain at least 32 characters");
        if(!notificationSecret.isBlank() && (notificationSecret.length()<32 || notificationSecret.equals(secret)))
            throw new IllegalArgumentException("Notification target credential must be separate and contain at least 32 characters");
        var filter=new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
                if(request.getRequestURI().startsWith("/internal/")) {
                    String given=request.getHeader("X-Service-Token");
                    boolean report=given!=null && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),given.getBytes(StandardCharsets.UTF_8));
                    boolean notification=given!=null && !notificationSecret.isBlank() && MessageDigest.isEqual(notificationSecret.getBytes(StandardCharsets.UTF_8),given.getBytes(StandardCharsets.UTF_8));
                    if(!report && !notification) {
                        response.setHeader("X-Correlation-Id",java.util.UUID.randomUUID().toString());
                        com.baotin.threat.api.Problems.write(response,401,"UNAUTHORIZED");return;
                    }
                    request.setAttribute("baotin.targetCaller.notification",notification);
                }
                chain.doFilter(request,response);
            }
        };
        return http.securityMatcher("/internal/**").csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
            .addFilterBefore(filter,UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(a->a.requestMatchers(org.springframework.http.HttpMethod.GET,"/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/internal/v1/targets/resolve").permitAll().anyRequest().denyAll())
            .build();
    }
}
