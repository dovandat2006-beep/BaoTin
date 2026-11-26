package com.baotin.auth.application;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

@Component
public class DeliveryMetrics {
    public DeliveryMetrics(IdentityStore store,MeterRegistry registry){
        for(var kind:DeliveryQueue.Kind.values()){
            Gauge.builder("baotin.auth.delivery.pending",store,s->s.jdbc().queryForObject("SELECT count(*) FROM auth."+kind.table+" WHERE "+kind.completed+" IS NULL AND attempts<"+kind.maxAttempts,Long.class)).tag("kind",kind.name().toLowerCase(java.util.Locale.ROOT)).register(registry);
            Gauge.builder("baotin.auth.delivery.failed",store,s->s.jdbc().queryForObject("SELECT count(*) FROM auth."+kind.table+" WHERE "+kind.completed+" IS NULL AND attempts>="+kind.maxAttempts,Long.class)).tag("kind",kind.name().toLowerCase(java.util.Locale.ROOT)).register(registry);
        }
    }
}
