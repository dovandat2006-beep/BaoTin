package com.baotin.notification.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    public record UnreadCount(long unreadCount) {}
    public record MarkAllResult(int updated) {}
    public record PreferencesPatch(Boolean riskChangeEnabled, Boolean reportStatusEnabled,
                                   Boolean pushEnabled, Boolean inAppEnabled) {}
    public record DeviceRequest(@NotBlank @Size(max = 16) String platform,
                                @NotBlank @Size(max = 512) String token,
                                @NotBlank @Size(max = 128) String deviceId) {}
    public record DevicesResponse(List<DeviceResource> items) {}

    private final com.baotin.notification.application.NotificationService service;

    public NotificationController(com.baotin.notification.application.NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<NotificationResource> list(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @RequestParam(defaultValue = "false") boolean unread) {
        paging(page, size);
        return service.list(Caller.id(jwt), page, size, unread);
    }

    @GetMapping("/unread-count")
    public UnreadCount unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return new UnreadCount(service.unreadCount(Caller.id(jwt)));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.markRead(Caller.id(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public MarkAllResult readAll(@AuthenticationPrincipal Jwt jwt) {
        return new MarkAllResult(service.markAllRead(Caller.id(jwt)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(Caller.id(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    public PreferencesResource preferences(@AuthenticationPrincipal Jwt jwt) {
        return service.preferences(Caller.id(jwt));
    }

    @PatchMapping("/preferences")
    public PreferencesResource updatePreferences(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody PreferencesPatch request) {
        return service.updatePreferences(Caller.id(jwt), request.riskChangeEnabled(), request.reportStatusEnabled(),
            request.pushEnabled(), request.inAppEnabled());
    }

    @GetMapping("/devices")
    public DevicesResponse devices(@AuthenticationPrincipal Jwt jwt) {
        return new DevicesResponse(service.devices(Caller.id(jwt)));
    }

    @PostMapping("/devices")
    public ResponseEntity<DeviceResource> registerDevice(@AuthenticationPrincipal Jwt jwt,
                                                         @Valid @RequestBody DeviceRequest request) {
        DeviceResource device = service.registerDevice(Caller.id(jwt), request.platform(), request.token(), request.deviceId());
        return ResponseEntity.created(URI.create("/api/v1/notifications/devices/" + device.id())).body(device);
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> deleteDevice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.removeDevice(Caller.id(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private static void paging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiFailure(400, "INVALID_PAGING", "page must be >= 0 and size must be between 1 and 100");
        }
    }
}
