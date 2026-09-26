package com.homeease.backend.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;

import com.homeease.backend.model.entity.Notification;
import com.homeease.backend.model.enums.NotifyStatus;
import com.homeease.backend.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class FcmNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(FcmNotificationService.class);

    private final NotificationRepository notificationRepository;

    public FcmNotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public void sendPushNotification(UUID recipientUserId, String title, String body) {
        try {
            Message message = Message.builder()
                    .putData("title", title)
                    .putData("body", body)
                    .setTopic("user_" + recipientUserId)
                    .build();

            FirebaseMessaging.getInstance().sendAsync(message);
            logger.info("FCM push notification dispatched to topic user_{}: {}", recipientUserId, title);
        } catch (Exception e) {
            logger.warn("FCM push notification send fallback: {}", e.getMessage());
        }

        // Persist notification record
        Notification notification = Notification.builder()
                .title(title)
                .message(body)
                .type("PUSH")
                .scheduledAt(Instant.now())
                .sentAt(Instant.now())
                .status(NotifyStatus.SENT)
                .build();

        notificationRepository.save(notification);
    }
}
