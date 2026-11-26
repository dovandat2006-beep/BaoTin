package com.baotin.notification.infrastructure;

import java.util.Map;

public interface PushSender {
    record PushMessage(String title, String body, Map<String, String> data) {}
    record PushResult(boolean delivered, boolean invalidToken, String error) {}

    boolean available();
    PushResult send(String token, PushMessage message);
}
