package com.baotin.auth.api;

import com.baotin.auth.application.AdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

@RestController
public class AdminController {
    private final AdminService admin;
    public AdminController(AdminService admin){this.admin=admin;}
    public record Role(@NotNull @Pattern(regexp="USER|MODERATOR") String role){}
    public record Status(@NotNull @Pattern(regexp="ACTIVE|LOCKED|DISABLED") String status){}
    public record StepUp(@NotNull @Pattern(regexp="[0-9]{6}") String code){}
    private UUID user(Jwt jwt){return UUID.fromString(jwt.getSubject());}
    private UUID sid(Jwt jwt){return UUID.fromString(jwt.getClaimAsString("sid"));}
    @PostMapping("/api/v1/auth/step-up") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stepUp(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody StepUp input){admin.stepUp(user(jwt),sid(jwt),input.code());}
    @GetMapping("/api/v1/auth/admin/users") @PreAuthorize("hasRole('ADMIN')")
    public Map<String,Object> users(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return admin.users(user(jwt),sid(jwt),page,size);}
    @PatchMapping("/api/v1/auth/admin/users/{id}/role") @PreAuthorize("hasRole('ADMIN')") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void role(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Role input){admin.role(user(jwt),sid(jwt),id,input.role());}
    @PatchMapping("/api/v1/auth/admin/users/{id}/status") @PreAuthorize("hasRole('ADMIN')") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void status(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Status input){admin.status(user(jwt),sid(jwt),id,input.status());}
}
