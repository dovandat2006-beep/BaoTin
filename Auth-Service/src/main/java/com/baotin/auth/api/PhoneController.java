package com.baotin.auth.api;

import com.baotin.auth.phone.PhoneService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
public class PhoneController {
    private final PhoneService phones;public PhoneController(PhoneService phones){this.phones=phones;}
    public record Request(@NotNull @Pattern(regexp="\\+[1-9][0-9]{7,14}") String phone,@Size(max=128) String password){}
    public record Proof(@NotBlank @Size(max=128) String challenge,@NotBlank @Size(max=16000) String idToken){}
    @PostMapping("/api/v1/auth/phone-verifications")
    public Map<String,Object> challenge(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Request input){return Map.of("challenge",phones.challenge(UUID.fromString(jwt.getSubject()),UUID.fromString(jwt.getClaimAsString("sid")),input.phone(),input.password()),"expiresIn",300,"testOnly",true);}
    @PostMapping("/api/v1/auth/verify-phone") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Proof input){phones.confirm(UUID.fromString(jwt.getSubject()),UUID.fromString(jwt.getClaimAsString("sid")),input.challenge(),input.idToken());}
}
