package com.baotin.auth.application;

import com.baotin.auth.api.ApiFailure;
import com.baotin.auth.security.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
    private final IdentityStore store;private final SigningKeys keys;private final Clock clock;private final AttemptLimits limits;
    public AdminService(IdentityStore store,SigningKeys keys,Clock clock,AttemptLimits limits){this.store=store;this.keys=keys;this.clock=clock;this.limits=limits;}
    private IdentityStore.User activeUser(UUID id){
        var u=store.locked(id).orElseThrow(ApiFailure::unauthorized);
        if(!"ACTIVE".equals(u.status()))throw ApiFailure.unauthorized();return u;
    }
    private Map<String,Object> session(UUID userId,UUID sessionId){
        var rows=store.jdbc().queryForList("SELECT * FROM auth.refresh_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>? FOR UPDATE",sessionId,userId,Timestamp.from(clock.instant()));
        if(rows.isEmpty())throw ApiFailure.unauthorized();return rows.getFirst();
    }
    @Transactional public void stepUp(UUID userId,UUID sessionId,String code){
        limits.check("step-up",userId.toString(),5,300);var u=activeUser(userId);
        if(!u.mfaEnabled())throw new ApiFailure(403,"MFA_REQUIRED","Authenticator required");
        var s=session(userId,sessionId);
        if(!Boolean.TRUE.equals(s.get("mfa_authenticated")))throw ApiFailure.unauthorized();
        // Step-up for privileged actions requires a fresh authenticator, never a recovery code.
        long step=AuthCrypto.matchedStep(keys.decrypt(u.mfaSecret()),code,clock.instant().getEpochSecond()/30,u.lastStep());
        if(step<0)throw ApiFailure.unauthorized();
        store.jdbc().update("UPDATE auth.users SET last_totp_step=? WHERE id=?",step,userId);
        store.jdbc().update("UPDATE auth.refresh_sessions SET step_up_at=? WHERE id=?",Timestamp.from(clock.instant()),sessionId);store.audit(userId,"STEP_UP_TOTP");
    }
    private void authorize(UUID actor,UUID sid){
        var u=store.byId(actor).orElseThrow(ApiFailure::unauthorized);
        if(!"ADMIN".equals(u.role()) || !"ACTIVE".equals(u.status()) || !u.mfaEnabled() || !u.emailVerified())throw new ApiFailure(403,"ADMIN_REQUIRED","Administrator role required");
        var s=session(actor,sid);Object stepUp=s.get("step_up_at");
        if(stepUp==null || ((Timestamp)stepUp).toInstant().isBefore(clock.instant().minusSeconds(300)) || !Boolean.TRUE.equals(s.get("mfa_authenticated")))throw new ApiFailure(403,"STEP_UP_REQUIRED","Recent authenticator step-up required");
    }
    private void lockPair(UUID a,UUID b){
        // Match PostgreSQL's UUID ordering, including privileged SQL functions.
        store.jdbc().queryForList("SELECT id FROM auth.users WHERE id IN (?,?) ORDER BY id FOR UPDATE",a,b);
        activeUser(a);if(store.byId(b).isEmpty())throw new ApiFailure(404,"USER_NOT_FOUND","User missing");
    }
    @Transactional public void role(UUID actor,UUID sid,UUID target,String newRole){
        lockPair(actor,target);authorize(actor,sid);
        var t=store.byId(target).orElseThrow(ApiFailure::unauthorized);
        if(actor.equals(target) || !Set.of("USER","MODERATOR").contains(newRole) || "ADMIN".equals(t.role()))throw new ApiFailure(403,"ROLE_TRANSITION_DENIED","Role transition denied");
        if("MODERATOR".equals(newRole) && (!t.mfaEnabled() || !t.emailVerified() || !"ACTIVE".equals(t.status())))throw new ApiFailure(409,"TARGET_MFA_REQUIRED","Target must verify email and confirm authenticator first");
        store.jdbc().queryForObject("SELECT auth.set_managed_role(?,?,?)",Object.class,actor,target,newRole);
    }
    @Transactional public void status(UUID actor,UUID sid,UUID target,String newStatus){
        lockPair(actor,target);authorize(actor,sid);var t=store.byId(target).orElseThrow(ApiFailure::unauthorized);
        if(actor.equals(target) || "ADMIN".equals(t.role()) || !Set.of("ACTIVE","LOCKED","DISABLED").contains(newStatus))throw new ApiFailure(403,"STATUS_TRANSITION_DENIED","Status transition denied");
        store.jdbc().queryForObject("SELECT auth.set_managed_status(?,?,?)",Object.class,actor,target,newStatus);
    }
    @Transactional public Map<String,Object> users(UUID actor,UUID sid,int page,int size){
        activeUser(actor);authorize(actor,sid);
        var rows=store.jdbc().queryForList("SELECT id,username,display_name,role,status,mfa_enabled FROM auth.users ORDER BY created_at DESC,id LIMIT ? OFFSET ?",size,(long)page*size);
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows)items.add(Map.of("id",row.get("id"),"username",row.get("username"),"displayName",row.get("display_name"),"role",row.get("role"),"status",row.get("status"),"mfaEnabled",row.get("mfa_enabled")));
        long total=store.jdbc().queryForObject("SELECT count(*) FROM auth.users",Long.class);
        return Map.of("items",items,"page",page,"size",size,"totalItems",total,"totalPages",(total+size-1)/size);
    }
}
