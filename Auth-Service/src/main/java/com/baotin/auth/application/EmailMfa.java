package com.baotin.auth.application;

import com.baotin.auth.api.ApiFailure;
import com.baotin.auth.security.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

/** Called inside the owner-locking AuthService transaction; never a TOTP fallback. */
@Component
public class EmailMfa {
    public record Pending(String challenge,long expiresIn,String mfaMethod) {}
    public record LoginProof(IdentityStore.User user,String authMethod) {}
    private final IdentityStore store;private final SigningKeys keys;private final Clock clock;private final AttemptLimits limits;
    private final SecureRandom random=new SecureRandom();
    public EmailMfa(IdentityStore store,SigningKeys keys,Clock clock,AttemptLimits limits){this.store=store;this.keys=keys;this.clock=clock;this.limits=limits;}
    public boolean available(){return Integer.parseInt(store.jdbc().queryForObject("SELECT value FROM auth.runtime_metadata WHERE name='schemaRevision'",String.class))>=10;}
    public void requireAvailable(){if(!available())throw new ApiFailure(503,"EMAIL_MFA_UNAVAILABLE","Email MFA migration is required");}
    private Timestamp now(){return Timestamp.from(clock.instant());}
    private void allowed(IdentityStore.User u,boolean enrollment){
        if(!"ACTIVE".equals(u.status()) || !"USER".equals(u.role()) || u.mfaEnabled() || u.email()==null || !u.emailVerified() || u.passwordHash()==null)throw ApiFailure.unauthorized();
        if(enrollment?u.emailMfaEnabled():!u.emailMfaEnabled())throw ApiFailure.unauthorized();
    }
    public void ownerSession(IdentityStore.User u,UUID sid,boolean mfa){
        if(sid==null || store.jdbc().queryForObject("SELECT count(*) FROM auth.refresh_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>? AND (? OR mfa_authenticated)",Long.class,sid,u.id(),now(),!mfa)!=1)throw ApiFailure.unauthorized();
    }
    private String code(){return String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));}
    private String hash(String token,String purpose,String code){return AuthCrypto.hash(token+":"+purpose+":"+code);}
    private void queue(IdentityStore.User u,String purpose,String code){
        String payload=u.email()+"\nBaoTin MFA_"+purpose+"\nYour single-use authentication code: "+code+"\nThis code expires in five minutes.";
        store.jdbc().update("INSERT INTO auth.mail_outbox(id,encrypted_payload) VALUES (?,?)",UUID.randomUUID(),keys.encrypt(payload.getBytes(StandardCharsets.UTF_8)));
    }
    public Pending issue(IdentityStore.User u,String purpose,UUID sid,String transport,String authMethod){
        requireAvailable();allowed(u,"ENROLL".equals(purpose));
        limits.check("email-mfa-delivery",u.id().toString(),6,600);
        if(!"LOGIN".equals(purpose))ownerSession(u,sid,!"ENROLL".equals(purpose));
        store.jdbc().update("UPDATE auth.email_mfa_challenges SET consumed_at=? WHERE user_id=? AND purpose=? AND consumed_at IS NULL",now(),u.id(),purpose);
        String token="e1."+AuthCrypto.token(),code=code();
        store.jdbc().update("INSERT INTO auth.email_mfa_challenges(token_hash,user_id,session_id,security_version,email_snapshot,purpose,transport,auth_method,code_hash,expires_at,last_sent_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            AuthCrypto.hash(token),u.id(),sid,u.version(),u.email(),purpose,transport,authMethod,hash(token,purpose,code),Timestamp.from(clock.instant().plusSeconds(300)),now());
        queue(u,purpose,code);return new Pending(token,300,"EMAIL");
    }
    private Map<String,Object> challenge(IdentityStore.User u,String token,String purpose,UUID sid,String transport){
        var rows=store.jdbc().queryForList("SELECT * FROM auth.email_mfa_challenges WHERE token_hash=? FOR UPDATE",AuthCrypto.hash(token));
        if(rows.isEmpty())throw ApiFailure.unauthorized();var c=rows.getFirst();
        if(!u.id().equals(c.get("user_id")) || !purpose.equals(c.get("purpose")) || !Objects.equals(sid,c.get("session_id")) || !Objects.equals(transport,c.get("transport")) ||
           u.version()!=((Number)c.get("security_version")).longValue() || !u.email().equals(c.get("email_snapshot")) || c.get("consumed_at")!=null ||
           ((Number)c.get("attempts")).intValue()>=5 || !((Timestamp)c.get("expires_at")).toInstant().isAfter(clock.instant()))throw ApiFailure.unauthorized();
        return c;
    }
    public void consume(IdentityStore.User u,String token,String code,String purpose,UUID sid,String transport){
        requireAvailable();allowed(u,"ENROLL".equals(purpose));
        if(!"LOGIN".equals(purpose))ownerSession(u,sid,!"ENROLL".equals(purpose));
        var c=challenge(u,token,purpose,sid,transport);
        store.jdbc().update("UPDATE auth.email_mfa_challenges SET attempts=attempts+1 WHERE token_hash=?",AuthCrypto.hash(token));
        String expected=(String)c.get("code_hash");
        if(code==null || !code.matches("[0-9]{6}") || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),hash(token,purpose,code).getBytes(StandardCharsets.US_ASCII)))throw ApiFailure.unauthorized();
        store.jdbc().update("UPDATE auth.email_mfa_challenges SET consumed_at=? WHERE token_hash=?",now(),AuthCrypto.hash(token));
    }
    private IdentityStore.User owner(String token){
        if(token==null || !token.startsWith("e1.") || !available())throw ApiFailure.unauthorized();
        var rows=store.jdbc().queryForList("SELECT user_id FROM auth.email_mfa_challenges WHERE token_hash=?",AuthCrypto.hash(token));
        if(rows.isEmpty())throw ApiFailure.unauthorized();
        return store.locked((UUID)rows.getFirst().get("user_id")).orElseThrow(ApiFailure::unauthorized);
    }
    public LoginProof login(String token,String code,String transport){
        var u=owner(token);consume(u,token,code,"LOGIN",null,transport);
        String method=store.jdbc().queryForObject("SELECT auth_method FROM auth.email_mfa_challenges WHERE token_hash=?",String.class,AuthCrypto.hash(token));
        store.audit(u.id(),"MFA_AUTHENTICATED");return new LoginProof(u,method);
    }
    public void resend(String token,String transport){
        var u=owner(token);allowed(u,false);var c=challenge(u,token,"LOGIN",null,transport);
        limits.check("email-mfa-delivery",u.id().toString(),6,600);
        if(((Number)c.get("deliveries")).intValue()>=3 || ((Timestamp)c.get("last_sent_at")).toInstant().isAfter(clock.instant().minusSeconds(30)))throw new ApiFailure(429,"RATE_LIMITED","Email challenge resend limit");
        String code=null;for(int i=0;i<16;i++){String candidate=code();if(!hash(token,"LOGIN",candidate).equals(c.get("code_hash"))){code=candidate;break;}}
        if(code==null)throw new ApiFailure(503,"AUTH_UNAVAILABLE","Authentication temporarily unavailable");
        store.jdbc().update("UPDATE auth.email_mfa_challenges SET code_hash=?,deliveries=deliveries+1,last_sent_at=? WHERE token_hash=?",hash(token,"LOGIN",code),now(),AuthCrypto.hash(token));queue(u,"LOGIN",code);
    }
    public void action(IdentityStore.User u,UUID sid,String composite,String purpose){
        if(composite==null)throw ApiFailure.unauthorized();int split=composite.lastIndexOf('.');
        if(split<=3)throw ApiFailure.unauthorized();consume(u,composite.substring(0,split),composite.substring(split+1),purpose,sid,null);
    }
    public void clearForTotp(UUID id){if(available())store.jdbc().update("UPDATE auth.users SET email_mfa_enabled=false WHERE id=?",id);}
}
