package com.homeease.notification.controller;

import com.homeease.notification.dto.NotificationDto.*;
import com.homeease.notification.service.NotificationCronWorker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/notifications", "/internal/notify", "/api/notifications"})
public class NotificationController {

    private final NotificationCronWorker notificationCronWorker;

    public NotificationController(NotificationCronWorker notificationCronWorker) {
        this.notificationCronWorker = notificationCronWorker;
    }

    @PostMapping({"/push", "/candidate-alert", "/assigned", "/pin", "/completed"})
    public ResponseEntity<PushResponse> dispatchPushNotification(@RequestBody PushRequest request) {
        return ResponseEntity.ok(notificationCronWorker.dispatchPushNotification(request));
    }
}
