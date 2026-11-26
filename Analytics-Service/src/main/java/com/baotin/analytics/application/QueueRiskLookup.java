package com.baotin.analytics.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@FunctionalInterface
public interface QueueRiskLookup {

    Map<UUID, RiskLevel> findRiskLevels(List<UUID> reportIds);
}
