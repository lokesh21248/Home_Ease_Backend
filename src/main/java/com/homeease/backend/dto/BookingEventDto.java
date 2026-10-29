package com.homeease.backend.dto;

import com.homeease.backend.model.enums.BookingActorType;
import com.homeease.backend.model.enums.BookingEventType;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.PaymentMode;
import com.homeease.backend.model.enums.TransactionState;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

public class BookingEventDto {

    public static final int SCHEMA_VERSION = 1;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BookingEventEnvelope {
        private UUID eventId;
        private BookingEventType eventType;
        private int schemaVersion;
        private Instant occurredAt;
        private UUID bookingId;
        private BookingStage previousStatus;
        private BookingStage newStatus;
        private BookingActorType actorType;
        private UUID actorId;
        private String correlationId;
        private UUID serviceId;
        private String servicePincode;
        private TransactionState paymentStatus;
        private TransactionState previousPaymentStatus;
        private PaymentMode paymentMethod;
        /** Extra line-item quantity when eventType is EXTRA_SERVICE_ADDED. */
        private Integer extraQuantity;
        private UUID extraSubServiceId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BookingHistoryItem {
        private UUID eventId;
        private BookingEventType eventType;
        private BookingStage previousStatus;
        private BookingStage newStatus;
        private BookingActorType actorType;
        private Instant occurredAt;
        private String correlationId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BookingHistoryPage {
        private java.util.List<BookingHistoryItem> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
    }
}
