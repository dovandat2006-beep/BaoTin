package com.baotin.auth.oauth;

import com.baotin.auth.api.ApiFailure;
import com.baotin.auth.application.*;
import com.baotin.auth.security.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OAuthService {
    public record Start(String authorizationUrl,String binding){}
    public record Result(AuthService.Login login,boolean linked,boolean onboardingRequired){public Result(AuthService.Login login,boolean linked){this(login,linked,false);}}
    private final IdentityStore store;private final SigningKeys keys;private final Clock clock;private final OAuthProviders providers;private final AuthService auth;private final Set<String> nativeUris,webUris;private final AttemptLimits limits;
    private final JsonMapper json=JsonMapper.builder().build();
    public OAuthService(IdentityStore store,SigningKeys keys,Clock clock,OAuthProviders providers,AuthService auth,Environment env,AttemptLimits limits){
        this.store=store;this.keys=keys;this.clock=clock;this.providers=providers;this.auth=auth;this.limits=limits;
        nativeUris=parse(env.getProperty("auth.oauth.native-return-uris","baotin://auth/callback"),false);webUris=parse(env.getProperty("auth.oauth.web-return-uris","https://localhost:9444/auth/callback"),true);
    }
    private Set<String> parse(String csv,boolean web){
        var values=new HashSet<String>();
        for(String value:csv.split(",")){
            value=value.trim();if(value.isEmpty())continue;
            try{
                URI uri=URI.create(value);String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
                boolean insecure=Set.of("http","javascript","data","file","about","ftp","ws","wss").contains(scheme);
                if(!uri.isAbsolute()||uri.isOpaque()||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||value.contains("*")||insecure||(web&&!scheme.equals("https")))throw new IllegalArgumentException();
            }catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid configured OAuth return URI");}
            values.add(value);
        }
        return Set.copyOf(values);
    }
    public static String challenge(String verifier){
        try{return Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));}
        catch(Exception e){throw new IllegalStateException("PKCE unavailable",e);}
    }
    @Transactional public Start start(String provider,String transport,String returnUri,String clientChallenge,UUID linkUserId,UUID sid){
        var config=providers.provider(provider);
        if(!Set.of("NATIVE","WEB").contains(transport) || !("WEB".equals(transport)?webUris:nativeUris).contains(returnUri) || !clientChallenge.matches("[A-Za-z0-9_-]{43}") || URI.create(returnUri).getFragment()!=null || URI.create(returnUri).getQuery()!=null)throw new ApiFailure(400,"OAUTH_REQUEST_INVALID","Invalid OAuth return URI or PKCE challenge");
        Long version=null;
        if(linkUserId!=null){
            var user=store.locked(linkUserId).orElseThrow(ApiFailure::unauthorized);
            if(!"ACTIVE".equals(user.status()) || !user.emailVerified())throw ApiFailure.unauthorized();
            var sessions=store.jdbc().queryForList("SELECT auth_time,step_up_at,mfa_authenticated FROM auth.refresh_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>?",sid,linkUserId,Timestamp.from(clock.instant()));
            if(sessions.isEmpty())throw ApiFailure.unauthorized();var session=sessions.getFirst();
            if(user.anyMfa()){
                if(!Boolean.TRUE.equals(session.get("mfa_authenticated")) || session.get("step_up_at")==null || ((Timestamp)session.get("step_up_at")).toInstant().isBefore(clock.instant().minusSeconds(300)))throw new ApiFailure(403,"STEP_UP_REQUIRED","Recent MFA step-up required to link identity");
            }else if(((Timestamp)session.get("auth_time")).toInstant().isBefore(clock.instant().minusSeconds(300)))throw new ApiFailure(403,"REAUTH_REQUIRED","Recent authentication required to link identity");
            version=user.version();
        }
        if(linkUserId==null && sid!=null)throw ApiFailure.unauthorized();
        String state=AuthCrypto.token(),nonce=AuthCrypto.token(),verifier=AuthCrypto.token(),binding="WEB".equals(transport)?AuthCrypto.token():null;
        store.jdbc().update("INSERT INTO auth.oauth_requests(state_hash,provider,transport,return_uri,client_challenge,nonce,encrypted_verifier,binding_hash,link_user_id,link_session_id,link_security_version,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            AuthCrypto.hash(state),config.code(),transport,returnUri,clientChallenge,nonce,keys.encrypt(verifier.getBytes(StandardCharsets.US_ASCII)),binding==null?null:AuthCrypto.hash(binding),linkUserId,sid,version,Timestamp.from(clock.instant().plusSeconds(300)));
        return new Start(providers.authorization(config,state,nonce,verifier),binding);
    }
    @Transactional(noRollbackFor=ApiFailure.class) public String callback(String provider,String state,String code,String binding){
        String hash=AuthCrypto.hash(state);var rows=store.jdbc().queryForList("SELECT * FROM auth.oauth_requests WHERE state_hash=? FOR UPDATE",hash);
        if(rows.isEmpty())throw ApiFailure.unauthorized();var row=rows.getFirst();
        if(row.get("consumed_at")!=null || !provider.toUpperCase(Locale.ROOT).equals(row.get("provider")) || !((Timestamp)row.get("expires_at")).toInstant().isAfter(clock.instant()))throw ApiFailure.unauthorized();
        if("WEB".equals(row.get("transport")) && (binding==null || !AuthCrypto.hash(binding).equals(row.get("binding_hash"))))throw new ApiFailure(403,"OAUTH_BINDING_INVALID","Browser binding is required");
        store.jdbc().update("UPDATE auth.oauth_requests SET consumed_at=? WHERE state_hash=?",Timestamp.from(clock.instant()),hash);
        String verifier=new String(keys.decrypt((byte[])row.get("encrypted_verifier")),StandardCharsets.US_ASCII);
        var identity=providers.exchange(providers.provider(provider),code,verifier,(String)row.get("nonce"));
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("provider",identity.provider());payload.put("subject",identity.subject());payload.put("email",identity.email());payload.put("verified",identity.emailVerified());payload.put("name",identity.name());payload.put("linkUserId",row.get("link_user_id"));payload.put("linkSessionId",row.get("link_session_id"));payload.put("linkVersion",row.get("link_security_version"));
        String ticket=AuthCrypto.token();
        store.jdbc().update("INSERT INTO auth.oauth_tickets(ticket_hash,encrypted_payload,client_challenge,transport,expires_at) VALUES (?,?,?,?,?)",AuthCrypto.hash(ticket),keys.encrypt(json.writeValueAsBytes(payload)),row.get("client_challenge"),row.get("transport"),Timestamp.from(clock.instant().plusSeconds(identity.email()==null?300:60)));
        return UriComponentsBuilder.fromUriString((String)row.get("return_uri")).queryParam("code",ticket).build().encode().toUriString();
    }
    @Transactional public String cancel(String provider,String state,String binding){
        var rows=store.jdbc().queryForList("SELECT * FROM auth.oauth_requests WHERE state_hash=? FOR UPDATE",AuthCrypto.hash(state));
        if(rows.isEmpty())throw ApiFailure.unauthorized();var row=rows.getFirst();
        if(row.get("consumed_at")!=null || !provider.toUpperCase(Locale.ROOT).equals(row.get("provider")) || !((Timestamp)row.get("expires_at")).toInstant().isAfter(clock.instant()))throw ApiFailure.unauthorized();
        if("WEB".equals(row.get("transport")) && (binding==null||!AuthCrypto.hash(binding).equals(row.get("binding_hash"))))throw new ApiFailure(403,"OAUTH_BINDING_INVALID","Browser binding is required");
        store.jdbc().update("UPDATE auth.oauth_requests SET consumed_at=? WHERE state_hash=?",Timestamp.from(clock.instant()),AuthCrypto.hash(state));
        return UriComponentsBuilder.fromUriString((String)row.get("return_uri")).queryParam("error","oauth_denied").build().encode().toUriString();
    }
    @Transactional public Result exchange(String ticket,String verifier,String transport){return exchangeWithContact(ticket,verifier,transport,null);}
    @Transactional public Result exchangeContact(String ticket,String verifier,String transport,String contactEmail){return exchangeWithContact(ticket,verifier,transport,contactEmail);}
    @SuppressWarnings("unchecked") private Result exchangeWithContact(String ticket,String verifier,String transport,String contactEmail){
        if(!verifier.matches("[A-Za-z0-9._~-]{43,128}"))throw new ApiFailure(400,"PKCE_INVALID","Invalid PKCE verifier");
        limits.check("oauth-exchange",AuthCrypto.hash(ticket),5,300);
        var rows=store.jdbc().queryForList("SELECT * FROM auth.oauth_tickets WHERE ticket_hash=? FOR UPDATE",AuthCrypto.hash(ticket));
        if(rows.isEmpty())throw ApiFailure.unauthorized();var row=rows.getFirst();
        if(row.get("consumed_at")!=null || !transport.equals(row.get("transport")) || !challenge(verifier).equals(row.get("client_challenge")) || !((Timestamp)row.get("expires_at")).toInstant().isAfter(clock.instant()))throw ApiFailure.unauthorized();
        Map<String,Object> payload=json.readValue(keys.decrypt((byte[])row.get("encrypted_payload")),Map.class);
        String email=(String)payload.get("email");boolean verified=Boolean.TRUE.equals(payload.get("verified"));
        if(contactEmail!=null && (contactEmail.isBlank()||contactEmail.length()>320||!contactEmail.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))throw new ApiFailure(400,"INVALID_INPUT","Contact email invalid");
        boolean knownIdentity=!store.jdbc().queryForList("SELECT user_id FROM auth.oauth_accounts WHERE provider=? AND provider_subject=?",payload.get("provider"),payload.get("subject")).isEmpty();
        if(email==null && !knownIdentity && payload.get("linkUserId")==null){
            if(contactEmail==null)return new Result(null,false,true);
            email=contactEmail;verified=false; // User-supplied contact is never a provider verification claim.
        }else if(contactEmail!=null)throw new ApiFailure(400,"CONTACT_NOT_REQUIRED","Provider identity contact cannot be overridden");
        Result result;
        if(payload.get("linkUserId")!=null){auth.linkProvider(UUID.fromString(payload.get("linkUserId").toString()),UUID.fromString(payload.get("linkSessionId").toString()),((Number)payload.get("linkVersion")).longValue(),(String)payload.get("provider"),(String)payload.get("subject"));result=new Result(null,true);}
        else result=new Result(auth.providerLogin((String)payload.get("provider"),(String)payload.get("subject"),email,verified,(String)payload.get("name"),transport),false);
        store.jdbc().update("UPDATE auth.oauth_tickets SET consumed_at=?,encrypted_payload=? WHERE ticket_hash=?",Timestamp.from(clock.instant()),new byte[0],AuthCrypto.hash(ticket));return result;
    }
}
