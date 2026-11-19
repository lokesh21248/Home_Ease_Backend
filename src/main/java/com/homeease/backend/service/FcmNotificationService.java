package com.homeease.backend.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.homeease.backend.model.entity.Notification;
import com.homeease.backend.model.enums.NotifyStatus;
import com.homeease.backend.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class FcmNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(FcmNotificationService.class);

    private final NotificationRepository notificationRepository;
    private final RedisTemplate<String, String> redisTemplate;

    public FcmNotificationService(NotificationRepository notificationRepository) {
        this(notificationRepository, null);
    }

    @Autowired
    public FcmNotificationService(NotificationRepository notificationRepository,
                                  @Autowired(required = false) RedisTemplate<String, String> redisTemplate) {
        this.notificationRepository = notificationRepository;
        this.redisTemplate = redisTemplate;
    }

    public void sendPushNotification(UUID recipientUserId, String title, String body) {
        if (recipientUserId == null) return;

        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                // High-priority Android notification payload to wake device from Doze mode
                com.google.firebase.messaging.Notification fcmNotif =
                        com.google.firebase.messaging.Notification.builder()
                                .setTitle(title)
                                .setBody(body)
                                .build();

                AndroidConfig androidConfig = AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setTitle(title)
                                .setBody(body)
                                .setChannelId("job_alerts")
                                .setPriority(AndroidNotification.Priority.MAX)
                                .setDefaultSound(true)
                                .setDefaultVibrateTimings(true)
                                .build())
                        .build();

                // 1. Send to user topic: user_{userId}
                Message topicMessage = Message.builder()
                        .setNotification(fcmNotif)
                        .putData("title", title)
                        .putData("body", body)
                        .setAndroidConfig(androidConfig)
                        .setTopic("user_" + recipientUserId)
                        .build();

                FirebaseMessaging.getInstance().sendAsync(topicMessage);
                logger.info("FCM push notification dispatched to topic user_{}: {}", recipientUserId, title);

                // 2. Also send directly to device token if stored in Redis (for devices not subscribed to topic)
                if (redisTemplate != null) {
                    try {
                        String deviceToken = redisTemplate.opsForValue().get("fcm_token:" + recipientUserId);
                        if (deviceToken != null && !deviceToken.isBlank() && !deviceToken.startsWith("fcm_mock_token_")) {
                            Message directMessage = Message.builder()
                                    .setNotification(fcmNotif)
                                    .putData("title", title)
                                    .putData("body", body)
                                    .setAndroidConfig(androidConfig)
                                    .setToken(deviceToken)
                                    .build();
                            FirebaseMessaging.getInstance().sendAsync(directMessage);
                            logger.info("FCM direct device push dispatched to user_{}", recipientUserId);
                        }
                    } catch (Exception redisEx) {
                        logger.debug("Could not dispatch to direct device token: {}", redisEx.getMessage());
                    }
                }
            } else {
                logger.warn("FCM push skipped: FirebaseApp is not initialized (credentials file missing). DB notification preserved.");
            }
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
