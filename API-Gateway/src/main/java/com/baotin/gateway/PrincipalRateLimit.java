package com.baotin.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
@Order(0) // Runs only after the security chain establishes the verified principal.
public class PrincipalRateLimit implements WebFilter {
    private static final RedisScript<Long> SCRIPT=RedisScript.of("local n=redis.call('INCR',KEYS[1]);if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end;return n",Long.class);
    private final ReactiveStringRedisTemplate redis;private final int limit,seconds;private final ServiceCatalog catalog;
    public PrincipalRateLimit(ReactiveStringRedisTemplate redis,@Value("${gateway.principal-limit:100}") int limit,@Value("${gateway.principal-window-seconds:600}") int seconds,ServiceCatalog catalog){if(limit<1||seconds<1)throw new IllegalArgumentException("Invalid principal rate settings");this.redis=redis;this.limit=limit;this.seconds=seconds;this.catalog=catalog;}
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain){
        if(!exchange.getRequest().getPath().value().startsWith("/api/v1/auth/") && catalog.endpoint(exchange).isEmpty())return chain.filter(exchange);
        return exchange.getPrincipal().flatMap(principal->{
            if(!(principal instanceof JwtAuthenticationToken jwt)||!jwt.isAuthenticated())return chain.filter(exchange).thenReturn(true);
            String key;
            try{key="gateway:principal:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jwt.getToken().getSubject().getBytes(StandardCharsets.UTF_8)))+":"+(Instant.now().getEpochSecond()/seconds);}
            catch(Exception e){return GatewayConfig.problem(exchange,503,"RATE_LIMIT_UNAVAILABLE").thenReturn(true);}
            return redis.execute(SCRIPT,List.of(key),List.of(Integer.toString(seconds))).single().onErrorReturn(-1L).flatMap(count->{
                if(count<0)return GatewayConfig.problem(exchange,503,"RATE_LIMIT_UNAVAILABLE").thenReturn(true);
                if(count>limit){exchange.getResponse().getHeaders().set("Retry-After",Integer.toString(seconds));return GatewayConfig.problem(exchange,429,"PRINCIPAL_RATE_LIMITED").thenReturn(true);}
                return chain.filter(exchange).thenReturn(true);
            });
        }).switchIfEmpty(Mono.defer(()->chain.filter(exchange).thenReturn(true))).then();
    }
}
