package com.baotin.auth.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryQueue {
    public enum Kind {
        MAIL("mail_outbox","sent_at",5),EVENT("outbox_events","published_at",10);
        final String table,completed;final int maxAttempts;
        Kind(String table,String completed,int maxAttempts){this.table=table;this.completed=completed;this.maxAttempts=maxAttempts;}
    }
    private final IdentityStore store;private final Clock clock;
    public DeliveryQueue(IdentityStore store,Clock clock){this.store=store;this.clock=clock;}
    @Transactional public List<Map<String,Object>> claim(Kind kind,int batch){
        String ordering=kind==Kind.EVENT?" AND NOT EXISTS(SELECT 1 FROM auth.outbox_events earlier WHERE earlier.aggregate_id=q.aggregate_id AND earlier.published_at IS NULL AND earlier.position<q.position)":"";
        String sort=kind==Kind.EVENT?"q.position":"q.created_at,q.id";
        var rows=store.jdbc().queryForList("SELECT q.* FROM auth."+kind.table+" q WHERE q."+kind.completed+" IS NULL AND q.attempts<? AND q.next_attempt_at<=? AND (q.lease_until IS NULL OR q.lease_until<?)"+ordering+" ORDER BY "+sort+" LIMIT ? FOR UPDATE OF q SKIP LOCKED",kind.maxAttempts,Timestamp.from(clock.instant()),Timestamp.from(clock.instant()),batch);
        for(var row:rows){UUID lease=UUID.randomUUID();int attempts=(Integer)row.get("attempts")+1;
            store.jdbc().update("UPDATE auth."+kind.table+" SET lease_id=?,lease_until=?,attempts=? WHERE id=?",lease,Timestamp.from(clock.instant().plusSeconds(300)),attempts,row.get("id"));row.put("lease_id",lease);row.put("attempts",attempts);
        }return rows;
    }
    @Transactional public void complete(Kind kind,UUID id,UUID lease){
        String clear=kind==Kind.MAIL?",encrypted_payload='\\x'::bytea":"";
        store.jdbc().update("UPDATE auth."+kind.table+" SET "+kind.completed+"=?,lease_id=NULL,lease_until=NULL"+clear+" WHERE id=? AND lease_id=? AND "+kind.completed+" IS NULL",Timestamp.from(clock.instant()),id,lease);
    }
    @Transactional public void fail(Kind kind,UUID id,UUID lease,int attempts){
        long delay=Math.min(3600,10L*(long)Math.pow(3,Math.min(attempts-1,6)));
        store.jdbc().update("UPDATE auth."+kind.table+" SET next_attempt_at=?,lease_id=NULL,lease_until=NULL WHERE id=? AND lease_id=? AND "+kind.completed+" IS NULL",Timestamp.from(clock.instant().plusSeconds(delay)),id,lease);
    }
}
