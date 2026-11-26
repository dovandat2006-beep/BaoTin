package com.baotin.gateway;

import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.http.*;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@org.springframework.boot.context.properties.EnableConfigurationProperties(ServiceCatalog.class)
public class GatewayConfig {
    private static final String ADMIN_ROLE="ADMIN";
    @Bean RouteLocator routes(RouteLocatorBuilder builder,@Value("${gateway.auth-uri}") String auth,ServiceCatalog catalog){
        var routes=builder.routes()
            .route("auth-provider-proof",r->r.path("/api/v1/auth/oauth2/callback/google","/api/v1/auth/oauth2/callback/facebook","/api/v1/auth/verify-phone")
                .filters(f->f.dedupeResponseHeader("Access-Control-Allow-Origin Access-Control-Allow-Credentials","RETAIN_UNIQUE"))
                .metadata("response-timeout",15000L).uri(auth))
            .route("auth",r->r.path("/api/v1/auth/**","/.well-known/jwks.json")
                .filters(f->f.dedupeResponseHeader("Access-Control-Allow-Origin Access-Control-Allow-Credentials","RETAIN_UNIQUE"))
                .uri(auth));
        for(var ep:catalog.endpoints())routes.route("manual-"+ep.id(),r->r.path(ep.path()).and().method(ep.methods().toArray(HttpMethod[]::new))
            .filters(f->f.dedupeResponseHeader("Access-Control-Allow-Origin Access-Control-Allow-Credentials","RETAIN_UNIQUE"))
            .uri(catalog.services().get(ep.service()).toString()));
        return routes.build();
    }
    @Bean ReactiveJwtDecoder jwt(@Value("${gateway.jwks-uri}") String jwks,@Value("${gateway.issuer}") String issuer,@Value("${gateway.audience}") String audience){
        var decoder=NimbusReactiveJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).validateType(false).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(30)),new JwtIssuerValidator(issuer),token->{
            boolean valid="at+jwt".equals(token.getHeaders().get("typ")) && token.getHeaders().get("kid")!=null
                && token.getAudience()!=null && token.getAudience().contains(audience) && "access".equals(token.getClaimAsString("token_use"))
                && token.getIssuedAt()!=null && token.getExpiresAt()!=null && token.getNotBefore()!=null
                && token.getExpiresAt().isAfter(token.getIssuedAt()) && Duration.between(token.getIssuedAt(),token.getExpiresAt()).getSeconds()<=600
                && !token.getIssuedAt().isAfter(java.time.Instant.now().plusSeconds(30))
                && token.getSubject()!=null && token.getId()!=null && token.getClaimAsString("sid")!=null && token.getClaim("ver")!=null;
            return valid?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        }));return decoder;
    }
    public static Mono<Void> problem(ServerWebExchange exchange,int status,String code){
        String correlation=exchange.getResponse().getHeaders().getFirst("X-Correlation-Id");if(correlation==null){correlation=java.util.UUID.randomUUID().toString();exchange.getResponse().getHeaders().set("X-Correlation-Id",correlation);}
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String json="{\"type\":\"https://baotin.example/errors/"+code.toLowerCase(java.util.Locale.ROOT).replace('_','-')+"\",\"title\":\"Request rejected\",\"status\":"+status+",\"code\":\""+code+"\",\"correlationId\":\""+correlation+"\"}";
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
    @Bean SecurityWebFilterChain security(ServerHttpSecurity http,@Value("${gateway.allowed-origins}") String origins,ServiceCatalog catalog){
        var cors=new CorsConfiguration();cors.setAllowedOrigins(ServiceCatalog.origins(origins));cors.setAllowedMethods(java.util.List.of("GET","POST","PUT","PATCH","DELETE"));
        cors.setAllowedHeaders(java.util.List.of("Authorization","Content-Type","X-CSRF-TOKEN","Idempotency-Key"));cors.setAllowCredentials(true);
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",cors);
        return http.csrf(c->c.disable()).cors(c->c.configurationSource(source)).httpBasic(c->c.disable()).formLogin(c->c.disable())
            .authorizeExchange(a->{a
                .pathMatchers(HttpMethod.GET,"/actuator/health","/actuator/health/liveness","/actuator/health/readiness","/.well-known/jwks.json").permitAll()
                .pathMatchers(HttpMethod.POST,"/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/mfa/challenges","/api/v1/auth/mfa/email/challenges","/api/v1/auth/verify-email","/api/v1/auth/forgot-password","/api/v1/auth/reset-password").permitAll()
                .pathMatchers(HttpMethod.POST,"/api/v1/auth/web/login","/api/v1/auth/web/refresh","/api/v1/auth/web/logout","/api/v1/auth/web/mfa/challenges","/api/v1/auth/web/mfa/email/challenges").permitAll()
                .pathMatchers(HttpMethod.POST,"/api/v1/auth/oauth2/requests","/api/v1/auth/oauth2/tokens","/api/v1/auth/web/oauth2/tokens").permitAll()
                .pathMatchers(HttpMethod.GET,"/api/v1/auth/oauth2/callback/google","/api/v1/auth/oauth2/callback/facebook").permitAll()
                .pathMatchers(HttpMethod.POST,"/api/v1/auth/oauth2/link-requests").authenticated()
                .pathMatchers(HttpMethod.POST,"/api/v1/auth/password-initialization").authenticated()
                .pathMatchers(HttpMethod.POST,"/api/v1/auth/phone-verifications","/api/v1/auth/verify-phone").authenticated()
                .pathMatchers(HttpMethod.POST,"/api/v1/auth/mfa/recovery-codes","/api/v1/auth/mfa/email/enrollments","/api/v1/auth/mfa/email/enrollments/confirmation","/api/v1/auth/mfa/email/action-challenges","/api/v1/auth/mfa/email/step-up").authenticated()
                .pathMatchers(HttpMethod.GET,"/api/v1/auth/me","/api/v1/auth/sessions").authenticated()
                .pathMatchers(HttpMethod.PATCH,"/api/v1/auth/me").authenticated()
                .pathMatchers(HttpMethod.GET,"/api/v1/auth/admin/users").hasRole(ADMIN_ROLE)
                .pathMatchers(HttpMethod.PATCH,"/api/v1/auth/admin/users/*/role","/api/v1/auth/admin/users/*/status").hasRole(ADMIN_ROLE)
                .pathMatchers(HttpMethod.POST,"/api/v1/auth/step-up").authenticated()
                .pathMatchers(HttpMethod.DELETE,"/api/v1/auth/sessions/*").authenticated()
                .pathMatchers(HttpMethod.POST,"/api/v1/auth/logout","/api/v1/auth/logout-all","/api/v1/auth/mfa/enrollments","/api/v1/auth/mfa/enrollments/confirmation","/api/v1/auth/mfa/disable","/api/v1/auth/email-verifications","/api/v1/auth/password").authenticated()
                ;
                for(var ep:catalog.endpoints())for(var method:ep.methods()){
                    var access=a.pathMatchers(method,ep.path());
                    if(ep.path().startsWith("/api/v1/moderation/")||ep.path().startsWith("/api/v1/analytics/admin/"))access.hasAnyRole("MODERATOR",ADMIN_ROLE);
                    else if(ep.anonymous())access.permitAll();else access.authenticated();
                }
                a.anyExchange().denyAll();})
            .oauth2ResourceServer(r->r.jwt(j->{
                var roles=new org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter();
                roles.setAuthoritiesClaimName("roles");roles.setAuthorityPrefix("ROLE_");
                var converter=new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter();converter.setJwtGrantedAuthoritiesConverter(roles);
                j.jwtAuthenticationConverter(new org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter(converter));
            }).authenticationEntryPoint((exchange,e)->problem(exchange,401,"UNAUTHORIZED")))
            .exceptionHandling(e->e.authenticationEntryPoint((exchange,x)->problem(exchange,401,"UNAUTHORIZED")).accessDeniedHandler((exchange,x)->problem(exchange,403,"FORBIDDEN")))
            .build();
    }
}
