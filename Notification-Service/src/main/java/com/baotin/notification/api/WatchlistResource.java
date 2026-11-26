package com.baotin.notification.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WatchlistResource(String id, UUID urlTargetId, String url, String host,
                                BigDecimal score, BigDecimal previousScore, String level, String previousLevel,
                                String label, Instant createdAt, Instant updatedAt) {}
