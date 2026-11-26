package com.baotin.gateway;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** A stopped/restarting target produces a generic503, never raw connection diagnostics. */
@Component
@Order(-2147483647)
public class UpstreamFailure implements WebFilter {
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain){
        return chain.filter(exchange).onErrorResume(error->{
            Throwable cause=error;
            for(int i=0;cause!=null && i<8;i++,cause=cause.getCause()){
                if(cause instanceof java.net.ConnectException || cause instanceof java.util.concurrent.TimeoutException ||
                   cause instanceof reactor.netty.http.client.PrematureCloseException ||
                   cause instanceof io.netty.channel.ConnectTimeoutException || cause instanceof io.netty.handler.timeout.ReadTimeoutException){
                    if(exchange.getResponse().isCommitted())return Mono.error(error);
                    return GatewayConfig.problem(exchange,503,"UPSTREAM_UNAVAILABLE");
                }
            }
            return Mono.error(error);
        });
    }
}
