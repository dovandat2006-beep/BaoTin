package com.baotin.reputation.api;

import com.baotin.reputation.application.ReputationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/reputation/targets")
public class ReputationController {
    private final ReputationService service;

    public ReputationController(ReputationService service) {
        this.service = service;
    }

    @GetMapping("/{threatTargetId}")
    @PreAuthorize("hasAnyRole('ADMIN','MODERATOR') or hasAuthority('SCOPE_reputation.read')")
    public ReputationResponse get(@PathVariable UUID threatTargetId) {
        return service.get(threatTargetId);
    }

    @PostMapping("/{threatTargetId}/recalculate")
    @PreAuthorize("hasRole('ADMIN')")
    public ReputationResponse recalculate(@PathVariable UUID threatTargetId,
                                          @RequestBody(required = false) @Valid RecalculationRequest request,
                                          @RequestAttribute(name = "correlationId", required = false) String correlationId) {
        return service.recalculate(threatTargetId,
            request == null ? RecalculationRequest.empty() : request, correlationId);
    }
}
