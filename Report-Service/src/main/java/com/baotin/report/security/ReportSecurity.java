package com.baotin.report.security;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ReportSecurity {
    @Bean JwtDecoder jwtDecoder(@Value("${report.jwks-uri}") String jwks,@Value("${report.issuer}") String issuer,@Value("${report.audience}") String audience) {
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).validateType(false).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(30)),new JwtIssuerValidator(issuer),token->{
            boolean valid="at+jwt".equals(token.getHeaders().get("typ")) && token.getHeaders().get("kid")!=null
                && token.getAudience().contains(audience) && "access".equals(token.getClaimAsString("token_use"))
                && token.getIssuedAt()!=null && token.getExpiresAt()!=null && token.getNotBefore()!=null
                && token.getExpiresAt().isAfter(token.getIssuedAt()) && Duration.between(token.getIssuedAt(),token.getExpiresAt()).getSeconds()<=600
                && !token.getIssuedAt().isAfter(Instant.now().plusSeconds(30))
                && token.getSubject()!=null && token.getId()!=null && token.getClaimAsString("sid")!=null && token.getClaim("ver")!=null;
            try { java.util.UUID.fromString(token.getSubject()); } catch(Exception e) { valid=false; }
            return valid?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }));
        return decoder;
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        var roles=new JwtGrantedAuthoritiesConverter();roles.setAuthoritiesClaimName("roles");roles.setAuthorityPrefix("ROLE_");
        var converter=new JwtAuthenticationConverter();converter.setJwtGrantedAuthoritiesConverter(roles);
        return http.csrf(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(c->c.disable()).formLogin(c->c.disable())
            .authorizeHttpRequests(a->a
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll()
                .requestMatchers("/api/v1/moderation/**").hasAnyRole("MODERATOR","ADMIN")
                .requestMatchers("/api/v1/reports/**","/api/v1/reports").authenticated()
                .requestMatchers("/internal/v1/analytics/**").hasAnyRole("MODERATOR","ADMIN")
                .anyRequest().denyAll())
            .oauth2ResourceServer(r->r.jwt(j->j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((request,response,e)->{response.setStatus(401);response.setContentType("application/problem+json");response.getWriter().write("{\"status\":401,\"title\":\"Phiên đăng nhập không hợp lệ\",\"code\":\"UNAUTHORIZED\"}");}))
            .exceptionHandling(e->e.accessDeniedHandler((request,response,x)->{response.setStatus(403);response.setContentType("application/problem+json");response.getWriter().write("{\"status\":403,\"title\":\"Không có quyền truy cập\",\"code\":\"FORBIDDEN\"}");}))
            .build();
    }
}
