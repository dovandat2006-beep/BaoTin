package com.baotin.notification.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/watchlist")
public class WatchlistController {
    public record WatchRequest(@NotBlank @Size(max = 4096) String url) {}

    private final com.baotin.notification.application.WatchlistService service;

    public WatchlistController(com.baotin.notification.application.WatchlistService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<WatchlistResource> list(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiFailure(400, "INVALID_PAGING", "page must be >= 0 and size must be between 1 and 100");
        }
        return service.list(Caller.id(jwt), page, size);
    }

    @PostMapping
    public ResponseEntity<WatchlistResource> add(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody WatchRequest request) {
        WatchlistResource watch = service.add(Caller.id(jwt), request.url());
        return ResponseEntity.created(URI.create("/api/v1/watchlist/" + watch.id())).body(watch);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.remove(Caller.id(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
