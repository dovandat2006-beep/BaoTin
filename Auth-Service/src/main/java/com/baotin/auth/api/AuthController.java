package com.baotin.auth.api;

import com.baotin.auth.application.AuthService;
import com.baotin.auth.security.SigningKeys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
public class AuthController {
    private final AuthService service;
    private final SigningKeys keys;
    public AuthController(AuthService service,SigningKeys keys){this.service=service;this.keys=keys;}
    public record Registration(@NotBlank @Pattern(regexp="[a-zA-Z0-9._]{3,30}") String username,@Email @Size(max=320) String email,@Pattern(regexp="\\+[1-9][0-9]{7,14}") String phone,
        @NotBlank @Size(min=12,max=128) String password,@NotBlank @Size(max=120) String displayName){}
    public record Login(@NotBlank @Size(max=320) String identifier,@NotBlank @Size(max=128) String password){}
    public record Token(@NotBlank @Size(max=128) String token){}
    public record Refresh(@NotBlank @Size(max=128) String refreshToken){}
    public record Challenge(@NotBlank @Size(max=128) String challenge,@NotBlank @Size(max=128) String code){}
    public record Password(@NotBlank @Size(max=128) String password){}
    public record Code(@NotBlank @Size(max=128) String code){}
    public record Disable(@NotBlank @Size(max=128) String password,@NotBlank @Size(max=128) String code){}
    public record Reset(@NotBlank @Size(max=128) String token,@NotBlank @Size(min=12,max=128) String password){}
    public record Forgot(@NotBlank @Size(max=320) String identifier){}
    public record Profile(@NotBlank @Size(max=120) String displayName){}
    public record ChangePassword(@NotBlank @Size(max=128) String currentPassword,@NotBlank @Size(min=12,max=128) String newPassword,@Size(max=128) String code){}
    public record InitialPassword(@NotBlank @Size(min=12,max=128) String newPassword,@Size(max=128) String code){}
    public record Recovery(@NotBlank @Size(max=128) String password,@NotNull @Pattern(regexp="[0-9]{6}") String code){}
    public record EmailProof(@NotBlank @Size(max=128) String challenge,@NotNull @Pattern(regexp="[0-9]{6}") String code){}
    public record EmailResend(@NotBlank @Size(max=128) String challenge){}
    public record EmailAction(@NotBlank @Pattern(regexp="CHANGE_PASSWORD|DISABLE_MFA|STEP_UP") String purpose){}
    private UUID id(Jwt jwt){return UUID.fromString(jwt.getSubject());}
    @GetMapping("/.well-known/jwks.json") public Map<String,Object> jwks(){return keys.publicSet().toJSONObject();}
    @PostMapping("/api/v1/auth/register") @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Profile register(@Valid @RequestBody Registration input){return service.registerContact(input.username(),input.email(),input.phone(),input.password(),input.displayName());}
    @PostMapping("/api/v1/auth/login") public AuthService.Login login(@Valid @RequestBody Login input){return service.login(input.identifier(),input.password());}
    @PostMapping("/api/v1/auth/refresh") public AuthService.Tokens refresh(@Valid @RequestBody Refresh input){return service.refresh(input.refreshToken());}
    @PostMapping("/api/v1/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT) public void logout(@AuthenticationPrincipal Jwt jwt){service.logout(id(jwt),UUID.fromString(jwt.getClaimAsString("sid")));}
    @PostMapping("/api/v1/auth/logout-all") @ResponseStatus(HttpStatus.NO_CONTENT) public void logoutAll(@AuthenticationPrincipal Jwt jwt){service.logoutAll(id(jwt));}
    @GetMapping("/api/v1/auth/me") public AuthService.Profile me(@AuthenticationPrincipal Jwt jwt){return service.profile(id(jwt));}
    @PatchMapping("/api/v1/auth/me") public AuthService.Profile profile(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Profile input){return service.updateProfile(id(jwt),input.displayName());}
    @GetMapping("/api/v1/auth/sessions") public Map<String,Object> sessions(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return service.sessions(id(jwt),page,size);}
    @DeleteMapping("/api/v1/auth/sessions/{sid}") @ResponseStatus(HttpStatus.NO_CONTENT) public void revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID sid){service.logout(id(jwt),sid);}
    @PostMapping("/api/v1/auth/mfa/enrollments") public AuthService.Enrollment enroll(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Password input){return service.enroll(id(jwt),input.password());}
    @PostMapping("/api/v1/auth/mfa/enrollments/confirmation") public Map<String,List<String>> confirm(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Code input){return Map.of("recoveryCodes",service.confirmEnrollment(id(jwt),input.code()));}
    @PostMapping("/api/v1/auth/mfa/challenges") public AuthService.Tokens challenge(@Valid @RequestBody Challenge input){return service.completeChallenge(input.challenge(),input.code());}
    @PostMapping("/api/v1/auth/mfa/disable") @ResponseStatus(HttpStatus.NO_CONTENT) public void disable(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Disable input){service.disableMfa(id(jwt),UUID.fromString(jwt.getClaimAsString("sid")),input.password(),input.code());}
    @PostMapping("/api/v1/auth/verify-email") @ResponseStatus(HttpStatus.NO_CONTENT) public void verifyEmail(@Valid @RequestBody Token input){service.verifyEmail(input.token());}
    @PostMapping("/api/v1/auth/email-verifications") @ResponseStatus(HttpStatus.ACCEPTED) public void resend(@AuthenticationPrincipal Jwt jwt){service.resendEmail(id(jwt));}
    @PostMapping("/api/v1/auth/forgot-password") @ResponseStatus(HttpStatus.ACCEPTED) public void forgot(@Valid @RequestBody Forgot input){service.forgot(input.identifier());}
    @PostMapping("/api/v1/auth/reset-password") @ResponseStatus(HttpStatus.NO_CONTENT) public void reset(@Valid @RequestBody Reset input){service.resetPassword(input.token(),input.password());}
    @PostMapping("/api/v1/auth/password") @ResponseStatus(HttpStatus.NO_CONTENT) public void password(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ChangePassword input){service.changePassword(id(jwt),UUID.fromString(jwt.getClaimAsString("sid")),input.currentPassword(),input.newPassword(),input.code());}
    @PostMapping("/api/v1/auth/password-initialization") @ResponseStatus(HttpStatus.NO_CONTENT) public void initialPassword(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody InitialPassword input){service.initializePassword(id(jwt),UUID.fromString(jwt.getClaimAsString("sid")),input.newPassword(),input.code());}
    @PostMapping("/api/v1/auth/mfa/recovery-codes") public Map<String,List<String>> recoveryCodes(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Recovery input){return Map.of("recoveryCodes",service.regenerateRecoveryCodes(id(jwt),input.password(),input.code()));}
    @PostMapping("/api/v1/auth/mfa/email/enrollments") public com.baotin.auth.application.EmailMfa.Pending emailEnroll(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Password input){return service.enrollEmailMfa(id(jwt),UUID.fromString(jwt.getClaimAsString("sid")),input.password());}
    @PostMapping("/api/v1/auth/mfa/email/enrollments/confirmation") @ResponseStatus(HttpStatus.NO_CONTENT) public void emailConfirm(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody EmailProof input){service.confirmEmailMfa(id(jwt),UUID.fromString(jwt.getClaimAsString("sid")),input.challenge(),input.code());}
    @PostMapping("/api/v1/auth/mfa/email/action-challenges") public com.baotin.auth.application.EmailMfa.Pending emailAction(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody EmailAction input){return service.emailActionChallenge(id(jwt),UUID.fromString(jwt.getClaimAsString("sid")),input.purpose());}
    @PostMapping("/api/v1/auth/mfa/email/step-up") @ResponseStatus(HttpStatus.NO_CONTENT) public void emailStepUp(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody EmailProof input){service.emailStepUp(id(jwt),UUID.fromString(jwt.getClaimAsString("sid")),input.challenge(),input.code());}
    @PostMapping("/api/v1/auth/mfa/email/challenges") @ResponseStatus(HttpStatus.ACCEPTED) public void emailResend(@Valid @RequestBody EmailResend input){service.resendEmailMfa(input.challenge(),"NATIVE");}
}
