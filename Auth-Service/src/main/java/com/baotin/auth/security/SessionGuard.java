package com.baotin.auth.security;

import com.baotin.auth.application.IdentityStore;
import java.util.UUID;
import java.time.Clock;
import java.sql.Timestamp;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ServletException;
import java.io.IOException;

@Component
public class SessionGuard extends OncePerRequestFilter {
    private final IdentityStore store;
    private final Clock clock;
    private final com.baotin.auth.phone.PhonePolicy phones;
    public SessionGuard(IdentityStore store,Clock clock,com.baotin.auth.phone.PhonePolicy phones){this.store=store;this.clock=clock;this.phones=phones;}
    public boolean active(Jwt jwt){
        try {
            UUID userId=UUID.fromString(jwt.getSubject()), sid=UUID.fromString(jwt.getClaimAsString("sid"));
            var user=store.byId(userId).orElse(null);
            if(user==null || !"ACTIVE".equals(user.status()) || user.version()!=((Number)jwt.getClaim("ver")).longValue())return false;
            if(user.email()==null && !phones.trusted(user.phone()))return false;
            if(!jwt.getClaimAsStringList("roles").equals(java.util.List.of(user.role())))return false;
            if(!"USER".equals(user.role()) && !user.mfaEnabled())return false;
            return store.jdbc().queryForObject("SELECT count(*) FROM auth.refresh_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>? AND (? OR mfa_authenticated)",Long.class,
                sid,userId,Timestamp.from(clock.instant()),!user.anyMfa())==1;
        }catch(Exception e){return false;}
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication instanceof JwtAuthenticationToken bearer && !active(bearer.getToken())){
            SecurityContextHolder.clearContext();ServletProblems.write(request,response,401,"SESSION_REVOKED");return;
        }chain.doFilter(request,response);
    }
}
