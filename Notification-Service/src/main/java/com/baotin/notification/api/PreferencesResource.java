package com.baotin.notification.api;

import java.time.Instant;

public record PreferencesResource(boolean riskChangeEnabled, boolean reportStatusEnabled,
                                  boolean pushEnabled, boolean inAppEnabled, Instant updatedAt) {}
