package com.baotin.auth.application;

import com.baotin.auth.api.ApiFailure;
import com.baotin.auth.security.*;
import com.baotin.auth.phone.PhonePolicy;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

@Service
public class AuthService {
    public record Tokens(String accessToken,String refreshToken,String tokenType,long expiresIn) {}
    public record Login(Tokens tokens,String challenge,boolean mfaRequired,String mfaMethod) {
        public Login(Tokens t,String c,boolean required){this(t,c,required,required?"TOTP":"NONE");}
    }
    public record Profile(UUID id,String username,String email,String displayName,String role,boolean emailVerified,boolean mfaEnabled,String phone,boolean phoneVerified,String mfaMethod) {}
    public record Enrollment(String secret,String otpauthUri) {}
    private final IdentityStore store;
    private final PasswordEncoder passwords;
    private final SigningKeys keys;
    private final JwtTokens jwt;
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String dummyHash;
    private final AttemptLimits limits;
    private final PhonePolicy phones;
    private final EmailMfa emailMfa;
    public AuthService(IdentityStore store,PasswordEncoder passwords,SigningKeys keys,JwtTokens jwt,JwtEncoder encoder,Clock clock,AttemptLimits limits,PhonePolicy phones,EmailMfa emailMfa){
        this.store=store;this.passwords=passwords;this.keys=keys;this.jwt=jwt;this.encoder=encoder;this.clock=clock;
        dummyHash=passwords.encode(AuthCrypto.token());
        this.limits=limits;
        this.phones=phones;this.emailMfa=emailMfa;
    }
    private Timestamp ts(Instant value){return Timestamp.from(value);}
    private String normalize(String value){return value.trim().toLowerCase(Locale.ROOT);}
    private IdentityStore.User locked(UUID id){return store.locked(id).orElseThrow(ApiFailure::unauthorized);}
    private void active(IdentityStore.User u){if(!"ACTIVE".equals(u.status()))throw ApiFailure.unauthorized();}
    private void verified(IdentityStore.User u){if(!u.emailVerified() && !(phones.trusted(u.phone())&&u.phoneVerified()))throw new ApiFailure(403,"EMAIL_VERIFICATION_REQUIRED","Verify a contact first");if(u.email()==null)phones.require(u.phone());}
    public Profile profile(UUID userId){var u=store.byId(userId).orElseThrow(ApiFailure::unauthorized);return new Profile(u.id(),u.username(),u.email(),u.displayName(),u.role(),u.emailVerified(),u.anyMfa(),u.phone(),phones.trusted(u.phone())&&u.phoneVerified(),u.mfaMethod());}

    @Transactional public Profile register(String username,String email,String password,String displayName){
        return registerIdentity(username,email,null,password,displayName);
    }
    @Transactional public Profile registerContact(String username,String email,String phone,String password,String displayName){
        return registerIdentity(username,email,phone,password,displayName);
    }
    private Profile registerIdentity(String username,String email,String phone,String password,String displayName){
        String normalizedEmail=email==null || email.isBlank()?null:normalize(email);
        if(normalizedEmail==null && phone==null)throw new ApiFailure(400,"CONTACT_REQUIRED","Email or phone is required");
        if(phone!=null)phones.require(phone);
        UUID id=UUID.randomUUID();
        store.jdbc().update("INSERT INTO auth.users(id,username,email,phone,password_hash,display_name) VALUES (?,?,?,?,?,?)",id,normalize(username),normalizedEmail,phone,passwords.encode(password),displayName.trim());
        if(normalizedEmail!=null)verification(id,"VERIFY_EMAIL",normalizedEmail);store.audit(id,"REGISTERED");return profile(id);
    }
    @Transactional public Login login(String identifier,String password){return loginWithTransport(identifier,password,"NATIVE");}
    @Transactional public Login webLogin(String identifier,String password){return loginWithTransport(identifier,password,"WEB");}
    private Login loginWithTransport(String identifier,String password,String transport){
        limits.check("login",normalize(identifier),10,300);
        var found=store.byIdentifier(normalize(identifier));
        boolean matches=passwords.matches(password,found.map(IdentityStore.User::passwordHash).orElse(dummyHash));
        if(!matches || found.isEmpty())throw ApiFailure.unauthorized();
        var user=locked(found.get().id());active(user);
        boolean phoneIdentifier=identifier.trim().matches("\\+[1-9][0-9]{7,14}");
        if(user.email()==null || phoneIdentifier){phones.require(user.phone());if(phoneIdentifier&&!user.phoneVerified())throw ApiFailure.unauthorized();}
        if(!passwords.matches(password,user.passwordHash()))throw ApiFailure.unauthorized();
        if(!"USER".equals(user.role()) && !user.mfaEnabled())throw new ApiFailure(403,"MFA_ENROLLMENT_REQUIRED","Privileged accounts require authenticator enrollment");
        if(user.emailMfaEnabled() && !user.mfaEnabled()){var pending=emailMfa.issue(user,"LOGIN",null,transport,"pwd");return new Login(null,pending.challenge(),true,"EMAIL");}
        if(user.mfaEnabled()) {
            String challenge=AuthCrypto.token();
            store.jdbc().update("INSERT INTO auth.mfa_challenges(token_hash,user_id,security_version,expires_at,transport) VALUES (?,?,?,?,?)",
                AuthCrypto.hash(challenge),user.id(),user.version(),ts(clock.instant().plusSeconds(300)),transport);
            return new Login(null,challenge,true);
        }
        return new Login(newSession(user,false,transport),null,false);
    }
    private Tokens newSession(IdentityStore.User user,boolean mfa,String transport){
        return newSession(user,mfa,transport,"pwd");
    }
    private Tokens newSession(IdentityStore.User user,boolean mfa,String transport,String method){
        UUID sid=UUID.randomUUID();Instant now=clock.instant();
        store.jdbc().update("INSERT INTO auth.refresh_sessions(id,user_id,created_at,expires_at,mfa_authenticated,auth_time,transport,auth_method) VALUES (?,?,?,?,?,?,?,?)",sid,user.id(),ts(now),ts(now.plus(30,ChronoUnit.DAYS)),mfa,ts(now),transport,method);
        store.audit(user.id(),"SESSION_CREATED");return rotated(user,sid,mfa,now,method);
    }
    private Tokens rotated(IdentityStore.User user,UUID sid,boolean mfa,Instant authTime,String method){
        String refresh=AuthCrypto.token();Instant now=clock.instant();
        store.jdbc().update("INSERT INTO auth.refresh_tokens(token_hash,session_id,created_at) VALUES (?,?,?)",AuthCrypto.hash(refresh),sid,ts(now));
        return new Tokens(jwt.issue(encoder,user.id(),sid,user.role(),user.version(),mfa,now,authTime,method),refresh,"Bearer",600);
    }
    @Transactional(noRollbackFor=ApiFailure.class) public Tokens refresh(String token){return refreshWithTransport(token,"NATIVE",null);}
    @Transactional(noRollbackFor=ApiFailure.class) public Tokens webRefresh(String token,String csrf){return refreshWithTransport(token,"WEB",csrf);}
    private Tokens refreshWithTransport(String token,String transport,String csrf){
        String hash=AuthCrypto.hash(token);
        var matches=store.jdbc().queryForList("SELECT s.id,s.user_id FROM auth.refresh_sessions s JOIN auth.refresh_tokens t ON s.id=t.session_id WHERE t.token_hash=?",hash);
        if(matches.isEmpty())throw ApiFailure.unauthorized();
        UUID sid=(UUID)matches.getFirst().get("id");var user=locked((UUID)matches.getFirst().get("user_id"));active(user);
        var session=store.jdbc().queryForMap("SELECT * FROM auth.refresh_sessions WHERE id=? FOR UPDATE",sid);
        if(!transport.equals(session.get("transport")))throw ApiFailure.unauthorized();
        if("WEB".equals(transport) && (csrf==null || !AuthCrypto.hash(csrf).equals(session.get("csrf_hash"))))throw new ApiFailure(403,"CSRF_INVALID","CSRF token is required");
        var used=store.jdbc().queryForMap("SELECT * FROM auth.refresh_tokens WHERE token_hash=? FOR UPDATE",hash);
        if(session.get("revoked_at")!=null || !((Timestamp)session.get("expires_at")).toInstant().isAfter(clock.instant()))throw ApiFailure.unauthorized();
        if(used.get("used_at")!=null){
            store.jdbc().update("UPDATE auth.refresh_sessions SET revoked_at=? WHERE id=?",ts(clock.instant()),sid);
            store.audit(user.id(),"REFRESH_REUSE_DETECTED");throw ApiFailure.unauthorized();
        }
        boolean mfa=(Boolean)session.get("mfa_authenticated");
        if((user.anyMfa() || !"USER".equals(user.role())) && !mfa)throw ApiFailure.unauthorized();
        store.jdbc().update("UPDATE auth.refresh_tokens SET used_at=? WHERE token_hash=?",ts(clock.instant()),hash);
        return rotated(user,sid,mfa,((Timestamp)session.get("auth_time")).toInstant(),(String)session.get("auth_method"));
    }
    @Transactional public void logout(UUID userId,UUID sid){
        locked(userId);store.jdbc().update("UPDATE auth.refresh_sessions SET revoked_at=? WHERE id=? AND user_id=? AND revoked_at IS NULL",ts(clock.instant()),sid,userId);
        store.audit(userId,"SESSION_REVOKED");
    }
    @Transactional public void logoutAll(UUID userId){var user=locked(userId);invalidate(user.id());store.audit(userId,"ALL_SESSIONS_REVOKED");}
    private void invalidate(UUID id){
        store.jdbc().update("UPDATE auth.users SET security_version=security_version+1 WHERE id=?",id);
        store.jdbc().update("UPDATE auth.refresh_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",ts(clock.instant()),id);
    }
    public Map<String,Object> sessions(UUID id,int page,int size){
        long total=store.jdbc().queryForObject("SELECT count(*) FROM auth.refresh_sessions WHERE user_id=? AND revoked_at IS NULL AND expires_at>?",Long.class,id,ts(clock.instant()));
        var items=store.jdbc().queryForList("SELECT id,created_at,expires_at,mfa_authenticated FROM auth.refresh_sessions WHERE user_id=? AND revoked_at IS NULL AND expires_at>? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",id,ts(clock.instant()),size,(long)page*size);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var row:items)result.add(Map.of("id",row.get("id"),"createdAt",((Timestamp)row.get("created_at")).toInstant(),"expiresAt",((Timestamp)row.get("expires_at")).toInstant(),"mfaAuthenticated",row.get("mfa_authenticated")));
        return Map.of("items",result,"page",page,"size",size,"totalItems",total,"totalPages",(total+size-1)/size);
    }
    @Transactional public Profile updateProfile(UUID id,String displayName){locked(id);store.jdbc().update("UPDATE auth.users SET display_name=? WHERE id=?",displayName.trim(),id);return profile(id);}

    @Transactional public Enrollment enroll(UUID id,String password){
        limits.check("mfa-enroll",id.toString(),5,300);
        var user=locked(id);active(user);verified(user);
        if(user.mfaEnabled())throw new ApiFailure(409,"MFA_ALREADY_ENABLED","Authenticator already enabled");
        if(!passwords.matches(password,user.passwordHash()))throw ApiFailure.unauthorized();
        byte[] secret=AuthCrypto.randomBytes(20);
        store.jdbc().update("INSERT INTO auth.mfa_enrollments(user_id,encrypted_secret,expires_at) VALUES (?,?,?) ON CONFLICT(user_id) DO UPDATE SET encrypted_secret=excluded.encrypted_secret,expires_at=excluded.expires_at",id,keys.encrypt(secret),ts(clock.instant().plusSeconds(300)));
        String encoded=AuthCrypto.base32(secret);
        String label=java.net.URLEncoder.encode("BaoTin:"+(user.email()==null?user.username():user.email()),StandardCharsets.UTF_8);
        return new Enrollment(encoded,"otpauth://totp/"+label+"?secret="+encoded+"&issuer=BaoTin&algorithm=SHA1&digits=6&period=30");
    }
    @Transactional public List<String> confirmEnrollment(UUID id,String code){
        limits.check("mfa-confirm",id.toString(),5,300);
        var user=locked(id);active(user);verified(user);
        if(user.mfaEnabled())throw new ApiFailure(409,"MFA_ALREADY_ENABLED","Authenticator already enabled");
        var rows=store.jdbc().queryForList("SELECT * FROM auth.mfa_enrollments WHERE user_id=? FOR UPDATE",id);
        if(rows.isEmpty() || !((Timestamp)rows.getFirst().get("expires_at")).toInstant().isAfter(clock.instant()))throw new ApiFailure(400,"ENROLLMENT_EXPIRED","Enrollment expired");
        byte[] encrypted=(byte[])rows.getFirst().get("encrypted_secret");
        long step=AuthCrypto.matchedStep(keys.decrypt(encrypted),code,clock.instant().getEpochSecond()/30,-1);
        if(step<0)throw ApiFailure.unauthorized();
        emailMfa.clearForTotp(id);
        store.jdbc().update("UPDATE auth.users SET mfa_enabled=true,mfa_secret=?,last_totp_step=? WHERE id=?",encrypted,step,id);
        store.jdbc().update("DELETE FROM auth.mfa_enrollments WHERE user_id=?",id);
        store.jdbc().update("DELETE FROM auth.recovery_codes WHERE user_id=?",id);
        List<String> codes=new ArrayList<>();
        for(int i=0;i<10;i++){String recovery=AuthCrypto.token();codes.add(recovery);store.jdbc().update("INSERT INTO auth.recovery_codes(user_id,code_hash) VALUES (?,?)",id,AuthCrypto.hash(recovery));}
        invalidate(id);store.audit(id,"MFA_ENABLED");return codes;
    }
    private boolean consumeFactor(IdentityStore.User user,String code){
        if(code==null)return false;
        long step=AuthCrypto.matchedStep(keys.decrypt(user.mfaSecret()),code,clock.instant().getEpochSecond()/30,user.lastStep());
        if(step>=0){store.jdbc().update("UPDATE auth.users SET last_totp_step=? WHERE id=?",step,user.id());return true;}
        return store.jdbc().update("UPDATE auth.recovery_codes SET consumed_at=? WHERE user_id=? AND code_hash=? AND consumed_at IS NULL",ts(clock.instant()),user.id(),AuthCrypto.hash(code))==1;
    }
    @Transactional(noRollbackFor=ApiFailure.class) public Tokens completeChallenge(String token,String code){return challengeWithTransport(token,code,"NATIVE");}
    @Transactional(noRollbackFor=ApiFailure.class) public Tokens completeWebChallenge(String token,String code){return challengeWithTransport(token,code,"WEB");}
    private Tokens challengeWithTransport(String token,String code,String transport){
        if(token.startsWith("e1.")){var proof=emailMfa.login(token,code,transport);return newSession(proof.user(),true,transport,proof.authMethod());}
        String hash=AuthCrypto.hash(token);var rows=store.jdbc().queryForList("SELECT user_id FROM auth.mfa_challenges WHERE token_hash=?",hash);
        if(rows.isEmpty())throw ApiFailure.unauthorized();
        var user=locked((UUID)rows.getFirst().get("user_id"));active(user);
        var c=store.jdbc().queryForMap("SELECT * FROM auth.mfa_challenges WHERE token_hash=? FOR UPDATE",hash);
        if(!transport.equals(c.get("transport")))throw ApiFailure.unauthorized();
        if(c.get("consumed_at")!=null || ((Integer)c.get("attempts"))>=5 || !((Timestamp)c.get("expires_at")).toInstant().isAfter(clock.instant()) || ((Long)c.get("security_version"))!=user.version() || !user.mfaEnabled())throw ApiFailure.unauthorized();
        store.jdbc().update("UPDATE auth.mfa_challenges SET attempts=attempts+1 WHERE token_hash=?",hash);
        if(!consumeFactor(user,code))throw ApiFailure.unauthorized();
        store.jdbc().update("UPDATE auth.mfa_challenges SET consumed_at=? WHERE token_hash=?",ts(clock.instant()),hash);
        store.audit(user.id(),"MFA_AUTHENTICATED");return newSession(user,true,transport,(String)c.get("auth_method"));
    }
    @Transactional(noRollbackFor=ApiFailure.class) public void disableMfa(UUID id,String password,String code){disableMfa(id,null,password,code);}
    @Transactional(noRollbackFor=ApiFailure.class) public void disableMfa(UUID id,UUID sid,String password,String code){
        limits.check("mfa-disable",id.toString(),5,300);
        var user=locked(id);active(user);
        if(!"USER".equals(user.role()))throw new ApiFailure(403,"MFA_REQUIRED","Privileged accounts cannot disable MFA");
        if(!user.anyMfa() || !passwords.matches(password,user.passwordHash()))throw ApiFailure.unauthorized();
        if(user.emailMfaEnabled()){emailMfa.action(user,sid,code,"DISABLE_MFA");emailMfa.clearForTotp(id);}else if(!consumeFactor(user,code))throw ApiFailure.unauthorized();
        store.jdbc().update("UPDATE auth.users SET mfa_enabled=false,mfa_secret=NULL,last_totp_step=-1 WHERE id=?",id);
        store.jdbc().update("DELETE FROM auth.recovery_codes WHERE user_id=?",id);invalidate(id);store.audit(id,"MFA_DISABLED");
    }
    private void verification(UUID id,String type,String email){
        String token=AuthCrypto.token();
        store.jdbc().update("INSERT INTO auth.verification_tokens(token_hash,user_id,type,expires_at) VALUES (?,?,?,?)",AuthCrypto.hash(token),id,type,ts(clock.instant().plusSeconds(type.equals("RESET_PASSWORD")?900:1800)));
        String payload=email+"\nBaoTin "+type+"\nYour single-use verification token: "+token;
        store.jdbc().update("INSERT INTO auth.mail_outbox(id,encrypted_payload) VALUES (?,?)",UUID.randomUUID(),keys.encrypt(payload.getBytes(StandardCharsets.UTF_8)));
    }
    @Transactional public void resendEmail(UUID id){limits.check("email-resend",id.toString(),3,3600);var user=locked(id);active(user);if(user.email()==null)throw new ApiFailure(400,"EMAIL_REQUIRED","Email is required");if(!user.emailVerified())verification(id,"VERIFY_EMAIL",user.email());}
    @Transactional public void forgot(String identifier){
        limits.check("forgot",normalize(identifier),3,3600);
        var u=store.byIdentifier(normalize(identifier));
        if(u.isPresent() && "ACTIVE".equals(u.get().status()) && u.get().emailVerified()){var user=locked(u.get().id());verification(user.id(),"RESET_PASSWORD",user.email());}
    }
    private UUID verificationUser(String hash,String type){
        var rows=store.jdbc().queryForList("SELECT user_id FROM auth.verification_tokens WHERE token_hash=? AND type=?",hash,type);
        if(rows.isEmpty())throw new ApiFailure(400,"INVALID_TOKEN","Token is invalid or expired");return (UUID)rows.getFirst().get("user_id");
    }
    private void consumeVerification(String hash,String type){
        int updated=store.jdbc().update("UPDATE auth.verification_tokens SET consumed_at=? WHERE token_hash=? AND type=? AND consumed_at IS NULL AND expires_at>?",ts(clock.instant()),hash,type,ts(clock.instant()));
        if(updated!=1)throw new ApiFailure(400,"INVALID_TOKEN","Token is invalid or expired");
    }
    @Transactional public void verifyEmail(String token){
        String hash=AuthCrypto.hash(token);var user=locked(verificationUser(hash,"VERIFY_EMAIL"));active(user);consumeVerification(hash,"VERIFY_EMAIL");
        store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",user.id());store.audit(user.id(),"EMAIL_VERIFIED");
    }
    @Transactional public void resetPassword(String token,String password){
        String hash=AuthCrypto.hash(token);var user=locked(verificationUser(hash,"RESET_PASSWORD"));active(user);consumeVerification(hash,"RESET_PASSWORD");
        store.jdbc().update("UPDATE auth.users SET password_hash=? WHERE id=?",passwords.encode(password),user.id());
        invalidate(user.id());store.jdbc().update("UPDATE auth.verification_tokens SET consumed_at=? WHERE user_id=? AND type='RESET_PASSWORD' AND consumed_at IS NULL",ts(clock.instant()),user.id());
        store.audit(user.id(),"PASSWORD_RESET");
    }
    @Transactional(noRollbackFor=ApiFailure.class) public void changePassword(UUID id,String oldPassword,String newPassword,String factor){changePassword(id,null,oldPassword,newPassword,factor);}
    @Transactional(noRollbackFor=ApiFailure.class) public void changePassword(UUID id,UUID sid,String oldPassword,String newPassword,String factor){
        limits.check("password-change",id.toString(),5,300);
        var user=locked(id);active(user);verified(user);
        if(!passwords.matches(oldPassword,user.passwordHash()))throw ApiFailure.unauthorized();
        if(user.emailMfaEnabled())emailMfa.action(user,sid,factor,"CHANGE_PASSWORD");else if(user.mfaEnabled() && !consumeFactor(user,factor))throw ApiFailure.unauthorized();
        store.jdbc().update("UPDATE auth.users SET password_hash=? WHERE id=?",passwords.encode(newPassword),id);invalidate(id);store.audit(id,"PASSWORD_CHANGED");
    }
    @Transactional public String bindCsrf(String refreshToken){
        var rows=store.jdbc().queryForList("SELECT session_id FROM auth.refresh_tokens WHERE token_hash=?",AuthCrypto.hash(refreshToken));
        if(rows.isEmpty())throw ApiFailure.unauthorized();
        String csrf=AuthCrypto.token();store.jdbc().update("UPDATE auth.refresh_sessions SET csrf_hash=? WHERE id=? AND transport='WEB'",AuthCrypto.hash(csrf),rows.getFirst().get("session_id"));return csrf;
    }
    @Transactional public void webLogout(String refreshToken,String csrf){
        var rows=store.jdbc().queryForList("SELECT s.* FROM auth.refresh_sessions s JOIN auth.refresh_tokens t ON s.id=t.session_id WHERE t.token_hash=?",AuthCrypto.hash(refreshToken));
        if(rows.isEmpty())throw ApiFailure.unauthorized();
        var user=locked((UUID)rows.getFirst().get("user_id"));
        var session=store.jdbc().queryForMap("SELECT * FROM auth.refresh_sessions WHERE id=? FOR UPDATE",rows.getFirst().get("id"));
        if(!"WEB".equals(session.get("transport")) || csrf==null || !AuthCrypto.hash(csrf).equals(session.get("csrf_hash")))throw new ApiFailure(403,"CSRF_INVALID","CSRF token is required");
        store.jdbc().update("UPDATE auth.refresh_sessions SET revoked_at=? WHERE id=?",ts(clock.instant()),session.get("id"));store.audit(user.id(),"SESSION_REVOKED");
    }
    @Transactional public Login providerLogin(String provider,String subject,String email,boolean verifiedEmail,String name,String transport){
        var linked=store.jdbc().queryForList("SELECT user_id FROM auth.oauth_accounts WHERE provider=? AND provider_subject=?",provider,subject);
        IdentityStore.User user;
        if(linked.isEmpty()){
            if(email==null || email.isBlank())throw new ApiFailure(409,"PROVIDER_EMAIL_REQUIRED","Provider must supply an email before creating an account");
            String normalized=normalize(email);
            if(!store.jdbc().queryForList("SELECT id FROM auth.users WHERE email=?",normalized).isEmpty())throw new ApiFailure(409,"ACCOUNT_LINK_REQUIRED","Sign in to the existing account and explicitly link this provider");
            UUID id=UUID.randomUUID();String username="u"+UUID.randomUUID().toString().replace("-","").substring(0,24);
            store.jdbc().update("INSERT INTO auth.users(id,username,email,password_hash,display_name) VALUES (?,?,?,NULL,?)",id,username,normalized,name==null||name.isBlank()?username:name.substring(0,Math.min(120,name.length())));
            if(verifiedEmail)store.jdbc().update("UPDATE auth.users SET email_verified=true WHERE id=?",id);
            else verification(id,"VERIFY_EMAIL",normalized);
            store.jdbc().update("INSERT INTO auth.oauth_accounts(id,user_id,provider,provider_subject) VALUES (?,?,?,?)",UUID.randomUUID(),id,provider,subject);
            store.audit(id,"OAUTH_REGISTERED");user=locked(id);
        }else user=locked((UUID)linked.getFirst().get("user_id"));
        active(user);
        if(!"USER".equals(user.role()) && !user.mfaEnabled())throw new ApiFailure(403,"MFA_ENROLLMENT_REQUIRED","Privileged accounts require authenticator enrollment");
        if(user.emailMfaEnabled() && !user.mfaEnabled()){var pending=emailMfa.issue(user,"LOGIN",null,transport,"federated");return new Login(null,pending.challenge(),true,"EMAIL");}
        if(user.mfaEnabled()){
            String challenge=AuthCrypto.token();store.jdbc().update("INSERT INTO auth.mfa_challenges(token_hash,user_id,security_version,expires_at,transport,auth_method) VALUES (?,?,?,?,?,?)",AuthCrypto.hash(challenge),user.id(),user.version(),ts(clock.instant().plusSeconds(300)),transport,"federated");
            return new Login(null,challenge,true);
        }
        return new Login(newSession(user,false,transport,"federated"),null,false);
    }
    @Transactional public void linkProvider(UUID id,UUID sid,long version,String provider,String subject){
        var user=locked(id);active(user);verified(user);
        if(user.version()!=version)throw ApiFailure.unauthorized();
        var sessions=store.jdbc().queryForList("SELECT * FROM auth.refresh_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>? FOR UPDATE",sid,id,ts(clock.instant()));
        if(sessions.isEmpty())throw ApiFailure.unauthorized();var session=sessions.getFirst();
        var proof=user.anyMfa()?session.get("step_up_at"):session.get("auth_time");
        if(user.anyMfa() && !Boolean.TRUE.equals(session.get("mfa_authenticated")))throw ApiFailure.unauthorized();
        if(proof==null || ((Timestamp)proof).toInstant().isBefore(clock.instant().minusSeconds(300)))throw new ApiFailure(403,"REAUTH_REQUIRED","Recent owner authentication required");
        store.jdbc().update("INSERT INTO auth.oauth_accounts(id,user_id,provider,provider_subject) VALUES (?,?,?,?)",UUID.randomUUID(),id,provider,subject);
        store.audit(id,"OAUTH_LINKED");
    }
    @Transactional public void initializePassword(UUID id,UUID sid,String password,String factor){
        limits.check("password-initialization",id.toString(),5,300);
        var user=locked(id);active(user);verified(user);
        if(user.passwordHash()!=null)throw new ApiFailure(409,"PASSWORD_ALREADY_SET","Use the change-password flow");
        var sessions=store.jdbc().queryForList("SELECT * FROM auth.refresh_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>? FOR UPDATE",sid,id,ts(clock.instant()));
        if(sessions.isEmpty())throw ApiFailure.unauthorized();var session=sessions.getFirst();
        if(!"federated".equals(session.get("auth_method")) || ((Timestamp)session.get("auth_time")).toInstant().isBefore(clock.instant().minusSeconds(300)))throw new ApiFailure(403,"REAUTH_REQUIRED","Recent provider authentication required");
        if(user.mfaEnabled() && !consumeFactor(user,factor))throw ApiFailure.unauthorized();
        store.jdbc().update("UPDATE auth.users SET password_hash=? WHERE id=?",passwords.encode(password),id);invalidate(id);store.audit(id,"PASSWORD_INITIALIZED");
    }
    @Transactional public List<String> regenerateRecoveryCodes(UUID id,String password,String code){
        limits.check("recovery-regeneration",id.toString(),5,300);var user=locked(id);active(user);verified(user);
        if(!user.mfaEnabled()||!passwords.matches(password,user.passwordHash()))throw ApiFailure.unauthorized();
        long step=AuthCrypto.matchedStep(keys.decrypt(user.mfaSecret()),code,clock.instant().getEpochSecond()/30,user.lastStep());
        if(step<0)throw ApiFailure.unauthorized();
        store.jdbc().update("UPDATE auth.users SET last_totp_step=? WHERE id=?",step,id);store.jdbc().update("DELETE FROM auth.recovery_codes WHERE user_id=?",id);
        List<String> codes=new ArrayList<>();for(int i=0;i<10;i++){String recovery=AuthCrypto.token();codes.add(recovery);store.jdbc().update("INSERT INTO auth.recovery_codes(user_id,code_hash) VALUES (?,?)",id,AuthCrypto.hash(recovery));}
        invalidate(id);store.audit(id,"MFA_RECOVERY_REGENERATED");return codes;
    }
    @Transactional public EmailMfa.Pending enrollEmailMfa(UUID id,UUID sid,String password){
        limits.check("email-mfa-enroll",id.toString(),5,300);var u=locked(id);active(u);verified(u);
        if(!passwords.matches(password,u.passwordHash()))throw ApiFailure.unauthorized();
        return emailMfa.issue(u,"ENROLL",sid,null,null);
    }
    @Transactional(noRollbackFor=ApiFailure.class) public void confirmEmailMfa(UUID id,UUID sid,String challenge,String code){
        var u=locked(id);active(u);emailMfa.consume(u,challenge,code,"ENROLL",sid,null);
        store.jdbc().update("UPDATE auth.users SET email_mfa_enabled=true WHERE id=?",id);
        invalidate(id);store.audit(id,"MFA_ENABLED");
    }
    @Transactional public EmailMfa.Pending emailActionChallenge(UUID id,UUID sid,String purpose){
        if(!Set.of("CHANGE_PASSWORD","DISABLE_MFA","STEP_UP").contains(purpose))throw new ApiFailure(400,"PURPOSE_INVALID","Unsupported factor purpose");
        var u=locked(id);active(u);return emailMfa.issue(u,purpose,sid,null,null);
    }
    @Transactional(noRollbackFor=ApiFailure.class) public void emailStepUp(UUID id,UUID sid,String challenge,String code){
        var u=locked(id);active(u);emailMfa.consume(u,challenge,code,"STEP_UP",sid,null);
        store.jdbc().update("UPDATE auth.refresh_sessions SET step_up_at=? WHERE id=? AND user_id=?",ts(clock.instant()),sid,id);store.audit(id,"STEP_UP_EMAIL");
    }
    @Transactional(noRollbackFor=ApiFailure.class) public void resendEmailMfa(String challenge,String transport){emailMfa.resend(challenge,transport);}

}
