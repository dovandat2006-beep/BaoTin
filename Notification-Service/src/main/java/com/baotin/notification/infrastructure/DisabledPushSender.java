package com.baotin.notification.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "notification.push.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledPushSender implements PushSender {
    @Override public boolean available() { return false; }
    @Override public PushResult send(String token, PushMessage message) {
        return new PushResult(false, false, "push provider is not configured");
    }
}
