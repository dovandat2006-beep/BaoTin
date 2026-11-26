package com.baotin.gateway;

import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.core.io.buffer.*;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.*;
import org.springframework.beans.factory.annotation.Value;

@Component
@Order(-200) // Bound body/rate before JWT verification as well as before upstream routing.
public class EdgeProtection implements WebFilter {
    private final ReactiveStringRedisTemplate redis;
    private final ServiceCatalog catalog;
    private final ProxyAddresses proxies;private final int rateLimit,readLimit,windowSeconds;
    private static final RedisScript<Long> SCRIPT=RedisScript.of("local n=redis.call('INCR',KEYS[1]);if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end;return n",Long.class);
    public EdgeProtection(ReactiveStringRedisTemplate redis,@Value("${gateway.trusted-proxy-cidrs:}") String trusted,@Value("${gateway.auth-write-limit:30}") int rateLimit,@Value("${gateway.auth-read-limit:100}") int readLimit,@Value("${gateway.rate-window-seconds:300}") int windowSeconds,ServiceCatalog catalog){this.redis=redis;this.catalog=catalog;proxies=new ProxyAddresses(trusted);if(rateLimit<1||readLimit<1||windowSeconds<1)throw new IllegalArgumentException("Rate settings must be positive");this.rateLimit=rateLimit;this.readLimit=readLimit;this.windowSeconds=windowSeconds;}
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain){
        var request=exchange.getRequest();String correlation=UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set("X-Correlation-Id",correlation);
        String raw=request.getURI().getRawPath();
        if(raw.contains("%") || raw.contains("\\") || raw.contains("//") || raw.matches(".*(/\\.\\.?)(/.*)?$"))return GatewayConfig.problem(exchange,400,"PATH_INVALID");
        String remote=request.getRemoteAddress()==null?"unknown":request.getRemoteAddress().getAddress().getHostAddress();
        String client;
        try{client=proxies.client(remote,String.join(",",request.getHeaders().getOrEmpty("X-Forwarded-For")));}catch(IllegalArgumentException e){return GatewayConfig.problem(exchange,400,"FORWARDED_IP_INVALID");}
        final String clientIp=client;
        var clean=request.mutate().headers(h->{
            for(String name:new ArrayList<>(h.headerNames())){
                String lower=name.toLowerCase(Locale.ROOT);
                if(lower.startsWith("x-user")||lower.startsWith("x-role")||lower.startsWith("x-baotin-")||lower.startsWith("tailscale-")||lower.startsWith("cf-")||lower.startsWith("x-forwarded-")||lower.equals("forwarded")||lower.equals("x-real-ip"))h.remove(name);
            }h.set("X-Correlation-Id",correlation);
            h.set("X-BaoTin-Client-IP",clientIp);
        }).build();
        exchange=exchange.mutate().request(clean).build();exchange.getResponse().getHeaders().set("X-Correlation-Id",correlation);exchange.getResponse().getHeaders().set("Cache-Control","no-store");
        final var current=exchange;
        int maxBody=clean.getMethod().name().equals("POST") && clean.getPath().value().matches("/api/v1/reports/[0-9a-fA-F-]{36}/evidence")
            && org.springframework.http.MediaType.MULTIPART_FORM_DATA.isCompatibleWith(clean.getHeaders().getContentType()) ? 11*1024*1024 : 65536;
        if(clean.getHeaders().getContentLength()>maxBody)return GatewayConfig.problem(current,413,"REQUEST_TOO_LARGE");
        boolean auth=clean.getPath().value().startsWith("/api/v1/auth/");
        var endpoint=catalog.endpoint(current);
        if((!auth && endpoint.isEmpty()) || "OPTIONS".equals(clean.getMethod().name()))return chain.filter(current);
        boolean write=Set.of("POST","PUT","PATCH","DELETE").contains(clean.getMethod().name());
        String bucket;
        try{String scope=auth?"auth":endpoint.orElseThrow().service();bucket=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((clientIp+":"+scope+(write?"-write":"-read")).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){return GatewayConfig.problem(current,503,"RATE_LIMIT_UNAVAILABLE");}
        String key="gateway:rate:"+bucket+":"+(Instant.now().getEpochSecond()/windowSeconds);
        return redis.execute(SCRIPT,List.of(key),List.of(Integer.toString(windowSeconds))).single()
            .onErrorResume(e->Mono.just(-1L))
            .flatMap(attempts->{
                if(attempts<0)return GatewayConfig.problem(current,503,"RATE_LIMIT_UNAVAILABLE");
                if(attempts>(write?rateLimit:readLimit)){current.getResponse().getHeaders().set("Retry-After",Integer.toString(windowSeconds));return GatewayConfig.problem(current,429,"RATE_LIMITED");}
                return DataBufferUtils.join(clean.getBody(),maxBody).defaultIfEmpty(current.getResponse().bufferFactory().wrap(new byte[0])).flatMap(buffer->{
                    byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);DataBufferUtils.release(buffer);
                    var decorated=new ServerHttpRequestDecorator(clean){@Override public Flux<DataBuffer> getBody(){return Flux.defer(()->Flux.just(current.getResponse().bufferFactory().wrap(bytes)));}};
                    return chain.filter(current.mutate().request(decorated).build());
                }).onErrorResume(DataBufferLimitException.class,e->GatewayConfig.problem(current,413,"REQUEST_TOO_LARGE"));
            });
    }
}
