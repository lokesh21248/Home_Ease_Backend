package com.homeease.notification.dto;

import lombok.*;

import java.util.UUID;

public class NotificationDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PushRequest {
        private UUID recipientUserId;
        private String title;
        private String message;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PushResponse {
        private Boolean status;
        private String message;
    }
}
