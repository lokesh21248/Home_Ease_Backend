package com.homeease.backend.service;

import com.homeease.backend.model.entity.Notification;
import com.homeease.backend.model.enums.NotifyStatus;
import com.homeease.backend.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@EnableScheduling
public class NotificationCronJob {

    private static final Logger logger = LoggerFactory.getLogger(NotificationCronJob.class);

    private final NotificationRepository notificationRepository;
    private final FcmNotificationService fcmNotificationService;

    public NotificationCronJob(NotificationRepository notificationRepository, FcmNotificationService fcmNotificationService) {
        this.notificationRepository = notificationRepository;
        this.fcmNotificationService = fcmNotificationService;
    }

    @Scheduled(cron = "0 * * * * *") // Runs every 60 seconds
    public void processPendingNotificationsQueue() {
        Instant now = Instant.now();
        List<Notification> pendingList = notificationRepository.findByStatusAndScheduledAtLessThanEqual(NotifyStatus.PENDING, now);

        if (pendingList.isEmpty()) {
            return;
        }

        logger.info("NotificationCronJob executing: Processing {} pending push notifications.", pendingList.size());

        for (Notification notification : pendingList) {
            try {
                UUID recipientId = notification.getUser() != null ? notification.getUser().getUserId() :
                        (notification.getWorker() != null ? notification.getWorker().getUser().getUserId() : null);

                if (recipientId != null) {
                    fcmNotificationService.sendPushNotification(recipientId, notification.getTitle(), notification.getMessage());
                }

                notification.setStatus(NotifyStatus.SENT);
                notification.setSentAt(Instant.now());
            } catch (Exception e) {
                logger.error("Failed to process notification {}: {}", notification.getNotificationId(), e.getMessage());
                notification.setStatus(NotifyStatus.FAILED);
            }
            notificationRepository.save(notification);
        }
    }
}
