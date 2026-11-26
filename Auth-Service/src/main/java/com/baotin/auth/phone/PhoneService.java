package com.baotin.auth.phone;

import com.baotin.auth.application.*;
import com.baotin.auth.api.ApiFailure;
import com.baotin.auth.security.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PhoneService {
    private final IdentityStore store;private final PhonePolicy policy;private final ObjectProvider<PhoneProofVerifier> verifier;private final PasswordEncoder passwords;private final Clock clock;private final AttemptLimits limits;
    public PhoneService(IdentityStore store,PhonePolicy policy,ObjectProvider<PhoneProofVerifier> verifier,PasswordEncoder passwords,Clock clock,AttemptLimits limits){this.store=store;this.policy=policy;this.verifier=verifier;this.passwords=passwords;this.clock=clock;this.limits=limits;}
    private IdentityStore.User active(UUID id){var user=store.locked(id).orElseThrow(ApiFailure::unauthorized);if(!"ACTIVE".equals(user.status()))throw ApiFailure.unauthorized();return user;}
    private Map<String,Object> session(UUID id,UUID sid){var rows=store.jdbc().queryForList("SELECT * FROM auth.refresh_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>? FOR UPDATE",sid,id,Timestamp.from(clock.instant()));if(rows.isEmpty())throw ApiFailure.unauthorized();return rows.getFirst();}
    @Transactional public String challenge(UUID id,UUID sid,String phone,String password){
        policy.require(phone);limits.check("phone-challenge",id.toString(),5,3600);var user=active(id);var session=session(id,sid);
        if(user.passwordHash()!=null){if(password==null || !passwords.matches(password,user.passwordHash()))throw ApiFailure.unauthorized();}
        else if(!"federated".equals(session.get("auth_method")) || ((Timestamp)session.get("auth_time")).toInstant().isBefore(clock.instant().minusSeconds(300)))throw new ApiFailure(403,"REAUTH_REQUIRED","Recent authentication required");
        if(user.anyMfa() && (!Boolean.TRUE.equals(session.get("mfa_authenticated")) || session.get("step_up_at")==null || ((Timestamp)session.get("step_up_at")).toInstant().isBefore(clock.instant().minusSeconds(300))))throw new ApiFailure(403,"STEP_UP_REQUIRED","Recent MFA step-up required");
        String token=AuthCrypto.token();store.jdbc().update("INSERT INTO auth.phone_challenges(token_hash,user_id,session_id,security_version,phone,expires_at) VALUES (?,?,?,?,?,?)",AuthCrypto.hash(token),id,sid,user.version(),phone,Timestamp.from(clock.instant().plusSeconds(300)));return token;
    }
    @Transactional(noRollbackFor=ApiFailure.class) public void confirm(UUID id,UUID sid,String challenge,String idToken){
        var user=active(id);session(id,sid);var rows=store.jdbc().queryForList("SELECT * FROM auth.phone_challenges WHERE token_hash=? FOR UPDATE",AuthCrypto.hash(challenge));
        if(rows.isEmpty())throw ApiFailure.unauthorized();var c=rows.getFirst();policy.require((String)c.get("phone"));
        if(!id.equals(c.get("user_id")) || !sid.equals(c.get("session_id")) || user.version()!=((Number)c.get("security_version")).longValue() || c.get("consumed_at")!=null || ((Integer)c.get("attempts"))>=5 || !((Timestamp)c.get("expires_at")).toInstant().isAfter(clock.instant()))throw ApiFailure.unauthorized();
        store.jdbc().update("UPDATE auth.phone_challenges SET attempts=attempts+1 WHERE token_hash=?",AuthCrypto.hash(challenge));
        var adapter=verifier.getIfAvailable();if(adapter==null)throw new ApiFailure(503,"PHONE_PROVIDER_UNAVAILABLE","Firebase adapter configuration required");
        var proof=adapter.verify(idToken);long now=clock.instant().getEpochSecond();
        if(!policy.project().equals(proof.project()) || proof.uid()==null || proof.uid().isBlank() || proof.uid().length()>128 || !"phone".equals(proof.provider()) || !c.get("phone").equals(proof.phone()) || proof.authTime()>now+30 || proof.authTime()<now-300 || proof.authTime()<((Timestamp)c.get("created_at")).toInstant().getEpochSecond()-30)throw new ApiFailure(401,"PHONE_PROOF_INVALID","Phone proof does not match challenge");
        var other=store.jdbc().queryForList("SELECT id FROM auth.users WHERE phone=? AND id<>?",proof.phone(),id);
        if(!other.isEmpty())throw new ApiFailure(409,"PHONE_UNAVAILABLE","Phone identity is not available");
        int accepted=store.jdbc().update("INSERT INTO auth.phone_proofs(project_id,firebase_uid,auth_time,user_id) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",proof.project(),proof.uid(),proof.authTime(),id);
        if(accepted!=1)throw new ApiFailure(401,"PHONE_PROOF_REUSED","Phone authentication event already consumed");
        store.jdbc().update("UPDATE auth.users SET phone=?,phone_verified=true,security_version=security_version+1 WHERE id=?",proof.phone(),id);
        store.jdbc().update("UPDATE auth.phone_challenges SET consumed_at=? WHERE token_hash=?",Timestamp.from(clock.instant()),AuthCrypto.hash(challenge));
        store.jdbc().update("UPDATE auth.refresh_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",Timestamp.from(clock.instant()),id);store.audit(id,"DEV_PHONE_VERIFIED");
    }
}
