package com.baotin.notification.infrastructure;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import java.io.FileInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** FCM adapter. Credentials are mounted by the operator; tokens are never logged. */
@Component
@ConditionalOnProperty(name = "notification.push.enabled", havingValue = "true")
public class FirebasePushSender implements PushSender {
    private static final String APP_NAME = "baotin-notification";

    private final FirebaseMessaging messaging;

    public FirebasePushSender(@Value("${notification.push.project-id:}") String projectId,
                              @Value("${notification.push.credentials-path:}") String credentialsPath) {
        if (projectId.isBlank() || credentialsPath.isBlank()) {
            throw new IllegalStateException("Firebase project id and credentials path are required when push is enabled");
        }
        try {
            FirebaseApp app = FirebaseApp.getApps().stream()
                .filter(existing -> APP_NAME.equals(existing.getName())).findFirst()
                .orElseGet(() -> {
                    try (var stream = new FileInputStream(credentialsPath)) {
                        var options = FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(stream))
                            .setProjectId(projectId)
                            .build();
                        return FirebaseApp.initializeApp(options, APP_NAME);
                    } catch (Exception e) {
                        throw new IllegalStateException("Firebase credentials could not be loaded", e);
                    }
                });
            this.messaging = FirebaseMessaging.getInstance(app);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Firebase push could not be initialized", e);
        }
    }

    @Override public boolean available() { return true; }

    @Override
    public PushResult send(String token, PushMessage message) {
        try {
            Message payload = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder().setTitle(message.title()).setBody(message.body()).build())
                .putAllData(message.data())
                .build();
            messaging.send(payload);
            return new PushResult(true, false, null);
        } catch (FirebaseMessagingException e) {
            boolean invalid = e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED
                || e.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT
                || e.getMessagingErrorCode() == MessagingErrorCode.SENDER_ID_MISMATCH;
            return new PushResult(false, invalid, e.getMessagingErrorCode() == null ? "FCM_ERROR" : e.getMessagingErrorCode().name());
        } catch (Exception e) {
            return new PushResult(false, false, "FCM_UNAVAILABLE");
        }
    }
}
