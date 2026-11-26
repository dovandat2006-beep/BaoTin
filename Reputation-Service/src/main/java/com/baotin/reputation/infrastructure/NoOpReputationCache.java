package com.baotin.reputation.infrastructure;

import com.baotin.reputation.api.ReputationResponse;
import com.baotin.reputation.application.ReputationCache;
import java.util.Optional;
import java.util.UUID;

public class NoOpReputationCache implements ReputationCache {
    @Override
    public Optional<ReputationResponse> get(UUID targetId) {
        return Optional.empty();
    }

    @Override
    public void put(UUID targetId, ReputationResponse response) {

    }

    @Override
    public void evict(UUID targetId) {

    }
}
