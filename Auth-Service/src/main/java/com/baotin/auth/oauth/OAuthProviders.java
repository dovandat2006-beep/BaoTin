package com.baotin.auth.oauth;

import com.baotin.auth.api.ApiFailure;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class OAuthProviders {
    private static final Logger LOG=LoggerFactory.getLogger(OAuthProviders.class);
    private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private enum Stage { TOKEN_EXCHANGE, OIDC, TOKEN_DEBUG, PROFILE }
    public record Identity(String provider,String subject,String email,boolean emailVerified,String name){}
    public record Provider(String code,String clientId,String clientSecret,String authorizationUri,String tokenUri,String callbackUri,String jwksUri,String issuer,String graphUri){}
    private final Environment env;private final RestClient client;private final Map<String,JwtDecoder> decoders=new java.util.concurrent.ConcurrentHashMap<>();
    public OAuthProviders(Environment env){
        this.env=env;var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        // Meta Graph also labels JSON as text/javascript. This converter parses
        // strict JSON only: no JSONP, JavaScript execution or trailing script.
        var json=new JacksonJsonHttpMessageConverter(JSON);
        json.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON,new MediaType("application","*+json"),
            new MediaType("text","javascript")));
        client=RestClient.builder().requestFactory(factory).configureMessageConverters(converters->converters.withJsonConverter(json)).build();
    }
    private String setting(String provider,String key,String defaultValue){return env.getProperty("auth.oauth."+provider.toLowerCase(Locale.ROOT)+"."+key,defaultValue);}
    public Provider provider(String value){
        String p=value.toUpperCase(Locale.ROOT);if(!Set.of("GOOGLE","FACEBOOK").contains(p))throw new ApiFailure(400,"OAUTH_PROVIDER_INVALID","Unsupported provider");
        String id=setting(p,"client-id",""),secret=setting(p,"client-secret",""),callback=setting(p,"callback-uri","");
        if(id.isBlank()||secret.isBlank()||callback.isBlank())throw new ApiFailure(503,"OAUTH_NOT_CONFIGURED","Provider configuration is required");
        String graph=setting(p,"graph-uri","https://graph.facebook.com/v25.0");
        var config=new Provider(p,id,secret,setting(p,"authorization-uri",p.equals("GOOGLE")?"https://accounts.google.com/o/oauth2/v2/auth":"https://www.facebook.com/v25.0/dialog/oauth"),
            setting(p,"token-uri",p.equals("GOOGLE")?"https://oauth2.googleapis.com/token":graph+"/oauth/access_token"),callback,
            setting(p,"jwks-uri","https://www.googleapis.com/oauth2/v3/certs"),setting(p,"issuer","https://accounts.google.com"),graph);
        if(!env.acceptsProfiles(Profiles.of("test"))){
            for(String url:List.of(config.authorizationUri(),config.tokenUri(),config.callbackUri(),config.jwksUri(),config.graphUri()))
                if(!"https".equalsIgnoreCase(URI.create(url).getScheme()))throw new IllegalStateException("OAuth URLs require HTTPS");
        }
        return config;
    }
    public String authorization(Provider p,String state,String nonce,String verifier){
        var uri=UriComponentsBuilder.fromUriString(p.authorizationUri()).queryParam("client_id",p.clientId()).queryParam("redirect_uri",p.callbackUri()).queryParam("response_type","code").queryParam("state",state);
        if(p.code().equals("GOOGLE"))uri.queryParam("scope","openid email profile").queryParam("nonce",nonce).queryParam("code_challenge",OAuthService.challenge(verifier)).queryParam("code_challenge_method","S256");
        else uri.queryParam("scope","email,public_profile");
        return uri.build().encode().toUriString();
    }
    @SuppressWarnings("unchecked") public Identity exchange(Provider p,String code,String verifier,String nonce){
        Stage stage=Stage.TOKEN_EXCHANGE;
        try{
            var form=new LinkedMultiValueMap<String,String>();form.add("grant_type","authorization_code");form.add("code",code);form.add("client_id",p.clientId());form.add("client_secret",p.clientSecret());form.add("redirect_uri",p.callbackUri());
            if(p.code().equals("GOOGLE"))form.add("code_verifier",verifier);
            Map<String,Object> tokens=client.post().uri(p.tokenUri()).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
            if(tokens==null)throw ApiFailure.unauthorized();
            if(p.code().equals("GOOGLE")){
                stage=Stage.OIDC;
                String idToken=(String)tokens.get("id_token");if(idToken==null)throw ApiFailure.unauthorized();
                Jwt decoded=decoders.computeIfAbsent(p.clientId()+":"+p.jwksUri(),unused->{
                    var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build());factory.setReadTimeout(Duration.ofSeconds(5));
                    var decoder=NimbusJwtDecoder.withJwkSetUri(p.jwksUri()).restOperations(new org.springframework.web.client.RestTemplate(factory)).build();
                    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(p.issuer()),jwt->jwt.getAudience().contains(p.clientId())?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));return decoder;
                }).decode(idToken);
                if(decoded.getExpiresAt()==null || decoded.getIssuedAt()==null || decoded.getIssuedAt().isAfter(java.time.Instant.now().plusSeconds(30)) || !decoded.getExpiresAt().isAfter(decoded.getIssuedAt()) || decoded.getSubject()==null || decoded.getSubject().isBlank() || !nonce.equals(decoded.getClaimAsString("nonce")) ||
                    (decoded.getAudience().size()>1 && !p.clientId().equals(decoded.getClaimAsString("azp"))))throw ApiFailure.unauthorized();
                return identity(p.code(),decoded.getSubject(),decoded.getClaimAsString("email"),Boolean.TRUE.equals(decoded.getClaimAsBoolean("email_verified")),decoded.getClaimAsString("name"));
            }
            String access=(String)tokens.get("access_token");if(access==null)throw ApiFailure.unauthorized();
            // Meta debug_token establishes application-bound token ownership before /me.
            stage=Stage.TOKEN_DEBUG;
            var debugUri=UriComponentsBuilder.fromUriString(p.graphUri()+"/debug_token").queryParam("input_token",access).queryParam("access_token",p.clientId()+"|"+p.clientSecret()).build().encode().toUri();
            Map<String,Object> debug=client.get().uri(debugUri).retrieve().body(Map.class);
            Map<String,Object> data=debug==null?null:(Map<String,Object>)debug.get("data");
            if(data==null || !Boolean.TRUE.equals(data.get("is_valid")) || !p.clientId().equals(data.get("app_id")) || !(data.get("user_id") instanceof String))throw ApiFailure.unauthorized();
            if(!(data.get("expires_at") instanceof Number exp) || exp.longValue()==0 || exp.longValue()<=java.time.Instant.now().getEpochSecond())throw ApiFailure.unauthorized();
            var uri=UriComponentsBuilder.fromUriString(p.graphUri()+"/me").queryParam("fields","id,name,email").queryParam("appsecret_proof",proof(p.clientSecret(),access)).build().encode().toUri();
            stage=Stage.PROFILE;
            Map<String,Object> profile=client.get().uri(uri).header("Authorization","Bearer "+access).retrieve().body(Map.class);
            if(profile==null || !data.get("user_id").equals(profile.get("id")))throw ApiFailure.unauthorized();
            // Facebook profile email is not accepted as verification proof.
            return identity(p.code(),(String)profile.get("id"),(String)profile.get("email"),false,(String)profile.get("name"));
        }catch(ApiFailure e){throw e;}catch(Exception e){
            // Never log exception messages, stack traces, URIs, bodies or credentials.
            int status=e instanceof RestClientResponseException response?response.getStatusCode().value():0;
            LOG.warn("OAUTH_PROVIDER_FAILURE provider={} stage={} httpStatus={} errorCode={} errorSubcode={}",
                p.code(),stage,status,errorNumber(e,"code"),errorNumber(e,"error_subcode"));
            throw new ApiFailure(401,"OAUTH_PROOF_INVALID","Provider response could not be verified");
        }
    }
    private int errorNumber(Exception error,String field){
        if(!(error instanceof RestClientResponseException response))return 0;
        String body=response.getResponseBodyAsString();
        if(body.length()>16384)return 0;
        try{
            Object payload=JSON.readValue(body,Map.class).get("error");
            if(payload instanceof Map<?,?> details && details.get(field) instanceof Number number){
                long value=number.longValue();if(value>=0 && value<=Integer.MAX_VALUE)return (int)value;
            }
        }catch(Exception ignored){/* A malformed/untrusted response is never a log payload. */}
        return 0;
    }
    private Identity identity(String provider,String subject,String email,boolean verified,String name){
        if(subject==null||subject.isBlank()||subject.length()>255)throw ApiFailure.unauthorized();
        if(email!=null && (email.length()>320||!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))throw ApiFailure.unauthorized();
        return new Identity(provider,subject,email,verified,name);
    }
    private String proof(String secret,String token)throws Exception{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));}
}
