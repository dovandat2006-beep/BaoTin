package com.baotin.reputation.application;

import com.baotin.reputation.api.ReputationResponse;
import java.util.Optional;
import java.util.UUID;

public interface ReputationCache {
    Optional<ReputationResponse> get(UUID targetId);

    void put(UUID targetId, ReputationResponse response);

    void evict(UUID targetId);
}
