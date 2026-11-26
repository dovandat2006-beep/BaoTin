package com.baotin.auth.api;

import com.baotin.auth.application.AuthService;
import com.baotin.auth.oauth.OAuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import java.util.*;

/** Separate cookie contract: never return refresh tokens in web JSON. */
@RestController
@RequestMapping("/api/v1/auth/web")
public class WebAuthController {
    private final AuthService service;private final Set<String> origins;private final OAuthService oauth;
    public WebAuthController(AuthService service,@Value("${auth.allowed-origins}") String origins,OAuthService oauth){this.service=service;this.origins=Set.copyOf(Arrays.stream(origins.split(",")).map(String::trim).toList());this.oauth=oauth;}
    private void origin(String value){if(value==null || !origins.contains(value))throw new ApiFailure(403,"ORIGIN_INVALID","Origin is not allowed");}
    private void cookie(HttpHeaders h,String name,String token,boolean httpOnly,long maxAge){
        h.add(HttpHeaders.SET_COOKIE,ResponseCookie.from(name,token).secure(true).httpOnly(httpOnly).sameSite("Lax").path("/").maxAge(maxAge).build().toString());
    }
    private ResponseEntity<Map<String,Object>> tokens(AuthService.Tokens t){
        String csrf=service.bindCsrf(t.refreshToken());var headers=new HttpHeaders();
        cookie(headers,"__Host-baotin-refresh",t.refreshToken(),true,30L*24*3600);cookie(headers,"__Host-baotin-csrf",csrf,false,30L*24*3600);
        return ResponseEntity.ok().headers(headers).body(Map.of("accessToken",t.accessToken(),"tokenType",t.tokenType(),"expiresIn",t.expiresIn(),"csrfToken",csrf));
    }
    @PostMapping("/login") public ResponseEntity<Map<String,Object>> login(@RequestHeader(name="Origin",required=false) String origin,@Valid @RequestBody AuthController.Login input){
        origin(origin);var result=service.webLogin(input.identifier(),input.password());
        if(result.mfaRequired())return ResponseEntity.ok(Map.of("mfaRequired",true,"challenge",result.challenge(),"mfaMethod",result.mfaMethod()));
        return tokens(result.tokens());
    }
    @PostMapping("/mfa/challenges") public ResponseEntity<Map<String,Object>> challenge(@RequestHeader(name="Origin",required=false) String origin,@Valid @RequestBody AuthController.Challenge input){
        origin(origin);return tokens(service.completeWebChallenge(input.challenge(),input.code()));
    }
    @PostMapping("/mfa/email/challenges") @ResponseStatus(HttpStatus.ACCEPTED) public void resendEmail(@RequestHeader(name="Origin",required=false) String origin,@Valid @RequestBody AuthController.EmailResend input){origin(origin);service.resendEmailMfa(input.challenge(),"WEB");}
    @PostMapping("/refresh") public ResponseEntity<Map<String,Object>> refresh(@RequestHeader(name="Origin",required=false) String origin,
        @CookieValue(name="__Host-baotin-refresh",required=false) String refresh,@RequestHeader(name="X-CSRF-TOKEN",required=false) String csrf){
        origin(origin);if(refresh==null)throw ApiFailure.unauthorized();return tokens(service.webRefresh(refresh,csrf));
    }
    @PostMapping("/logout") public ResponseEntity<Void> logout(@RequestHeader(name="Origin",required=false) String origin,
        @CookieValue(name="__Host-baotin-refresh",required=false) String refresh,@RequestHeader(name="X-CSRF-TOKEN",required=false) String csrf){
        origin(origin);if(refresh==null)throw ApiFailure.unauthorized();service.webLogout(refresh,csrf);
        var headers=new HttpHeaders();cookie(headers,"__Host-baotin-refresh","",true,0);cookie(headers,"__Host-baotin-csrf","",false,0);return ResponseEntity.noContent().headers(headers).build();
    }
    @PostMapping("/oauth2/tokens") public ResponseEntity<Map<String,Object>> oauth(@RequestHeader(name="Origin",required=false) String origin,@Valid @RequestBody OAuthController.Exchange input){
        origin(origin);var result=oauth.exchangeContact(input.code(),input.codeVerifier(),"WEB",input.contactEmail());
        if(result.onboardingRequired())return ResponseEntity.ok(Map.of("onboardingRequired",true,"contactRequired","email"));
        if(result.linked())return ResponseEntity.ok(Map.of("linked",true));
        if(result.login().mfaRequired())return ResponseEntity.ok(Map.of("mfaRequired",true,"challenge",result.login().challenge(),"mfaMethod",result.login().mfaMethod()));
        return tokens(result.login().tokens());
    }
}
