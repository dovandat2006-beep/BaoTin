package com.baotin.auth.api;

import com.baotin.auth.oauth.OAuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

@RestController
public class OAuthController {
    private final OAuthService oauth;private final Set<String> origins;
    public OAuthController(OAuthService oauth,@Value("${auth.allowed-origins}") String origins){this.oauth=oauth;this.origins=Set.copyOf(Arrays.stream(origins.split(",")).map(String::trim).toList());}
    public record Request(@NotNull @Pattern(regexp="GOOGLE|FACEBOOK") String provider,@NotNull @Pattern(regexp="WEB|NATIVE") String transport,
        @NotBlank @Size(max=500) String returnUri,@NotNull @Pattern(regexp="[A-Za-z0-9_-]{43}") String codeChallenge){}
    public record Exchange(@NotBlank @Size(max=128) String code,@NotNull @Pattern(regexp="[A-Za-z0-9._~-]{43,128}") String codeVerifier,@Email @Size(max=320) String contactEmail){}
    private ResponseEntity<Map<String,String>> start(Request input,String origin,UUID user,UUID sid){
        if("WEB".equals(input.transport()) && (origin==null || !origins.contains(origin)))throw new ApiFailure(403,"ORIGIN_INVALID","Origin is not allowed");
        var started=oauth.start(input.provider(),input.transport(),input.returnUri(),input.codeChallenge(),user,sid);
        var headers=new HttpHeaders();headers.setCacheControl("no-store");
        if(started.binding()!=null)headers.add(HttpHeaders.SET_COOKIE,ResponseCookie.from("__Host-baotin-oauth-binding",started.binding()).secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(300).build().toString());
        return ResponseEntity.ok().headers(headers).body(Map.of("authorizationUrl",started.authorizationUrl()));
    }
    @PostMapping("/api/v1/auth/oauth2/requests")
    public ResponseEntity<Map<String,String>> request(@Valid @RequestBody Request input,@RequestHeader(name="Origin",required=false) String origin){return start(input,origin,null,null);}
    @PostMapping("/api/v1/auth/oauth2/link-requests")
    public ResponseEntity<Map<String,String>> link(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Request input,@RequestHeader(name="Origin",required=false) String origin){return start(input,origin,UUID.fromString(jwt.getSubject()),UUID.fromString(jwt.getClaimAsString("sid")));}
    @GetMapping("/api/v1/auth/oauth2/callback/{provider}")
    public ResponseEntity<Void> callback(@PathVariable @Pattern(regexp="google|facebook") String provider,
        @RequestParam @NotBlank @Size(max=128) String state,@RequestParam(required=false) @Size(max=4096) String code,@RequestParam(required=false) @Size(max=120) String error,@CookieValue(name="__Host-baotin-oauth-binding",required=false) String binding){
        if(error==null && (code==null || code.isBlank()))throw new ApiFailure(400,"INVALID_INPUT","Provider code is required");
        var headers=new HttpHeaders();headers.setLocation(URI.create(error==null?oauth.callback(provider,state,code,binding):oauth.cancel(provider,state,binding)));headers.setCacheControl("no-store");headers.set("Referrer-Policy","no-referrer");
        headers.add(HttpHeaders.SET_COOKIE,ResponseCookie.from("__Host-baotin-oauth-binding","").secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(0).build().toString());
        return ResponseEntity.status(303).headers(headers).build();
    }
    @PostMapping("/api/v1/auth/oauth2/tokens")
    public Object exchange(@Valid @RequestBody Exchange input){var result=oauth.exchangeContact(input.code(),input.codeVerifier(),"NATIVE",input.contactEmail());if(result.onboardingRequired())return Map.of("onboardingRequired",true,"contactRequired","email");return result.linked()?Map.of("linked",true):result.login();}
}
