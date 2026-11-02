package com.homeease.backend.service;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class BookingNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(BookingNotificationService.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    private final FcmNotificationService fcmNotificationService;
    private final SimpMessagingTemplate messagingTemplate;

    public BookingNotificationService(FcmNotificationService fcmNotificationService,
                                      SimpMessagingTemplate messagingTemplate) {
        this.fcmNotificationService = fcmNotificationService;
        this.messagingTemplate = messagingTemplate;
    }

    public void notifyNewBookingRequest(Worker candidate, Booking booking) {
        if (candidate == null || candidate.getUser() == null) return;
        String serviceName = booking.getService() != null ? booking.getService().getName() : "Home Service";
        String typeLabel = "INSTANT".equalsIgnoreCase(booking.getBookingType().name()) ? "Instant Job" : "Scheduled Job";

        String title = "New " + typeLabel + " Alert!";
        String body = "Customer requested " + serviceName + " near your area. Tap to accept now!";

        fcmNotificationService.sendPushNotification(candidate.getUser().getUserId(), title, body);

        // Also broadcast WebSocket event for real-time mobile app push
        try {
            String customerName = booking.getRecipientName() != null && !booking.getRecipientName().isBlank()
                    ? booking.getRecipientName()
                    : (booking.getUser() != null ? booking.getUser().getFullName() : "Customer");
            String customerPhone = booking.getRecipientPhone() != null && !booking.getRecipientPhone().isBlank()
                    ? booking.getRecipientPhone()
                    : (booking.getUser() != null ? booking.getUser().getPhoneNumber() : "");
            String customerAddress = booking.getRecipientAddress() != null && !booking.getRecipientAddress().isBlank()
                    ? booking.getRecipientAddress()
                    : "Customer Location";

            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "NEW_JOB_REQUEST");
            payload.put("bookingId", booking.getBookingId().toString());
            payload.put("serviceName", serviceName);
            payload.put("subServiceName", serviceName);
            payload.put("bookingType", booking.getBookingType().name());
            payload.put("totalAmount", booking.getTotalAmount());
            payload.put("customerName", customerName);
            payload.put("customerPhone", customerPhone);
            payload.put("customerAddress", customerAddress);
            payload.put("customerLat", booking.getUserLat());
            payload.put("customerLng", booking.getUserLng());
            payload.put("userLat", booking.getUserLat());
            payload.put("userLng", booking.getUserLng());
            payload.put("status", booking.getStatus() != null ? booking.getStatus().name() : "REQUESTED");
            if (booking.getScheduledAt() != null) {
                payload.put("scheduledAt", booking.getScheduledAt().toString());
            }
            if (booking.getCreatedAt() != null) {
                payload.put("createdAt", booking.getCreatedAt().toString());
            }

            // Broadcast on workerId channel
            messagingTemplate.convertAndSend("/topic/worker/" + candidate.getWorkerId() + "/requests", payload);

            // Also broadcast on userId channel for client flexibility
            if (candidate.getUser() != null && candidate.getUser().getUserId() != null) {
                messagingTemplate.convertAndSend("/topic/worker/" + candidate.getUser().getUserId() + "/requests", payload);
            }
        } catch (Exception e) {
            logger.debug("WebSocket broadcast skipped: {}", e.getMessage());
        }
    }

    public void notifyWorkerAccepted(Booking booking) {
        if (booking.getUser() == null) return;
        String workerName = booking.getWorker() != null && booking.getWorker().getUser() != null
                ? booking.getWorker().getUser().getFullName() : "Professional";

        String title = "Partner Assigned!";
        String body = workerName + " accepted your request. Your 4-digit verification PIN is " + booking.getPinCode();

        fcmNotificationService.sendPushNotification(booking.getUser().getUserId(), title, body);

        // Broadcast to customer booking channel
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "WORKER_ACCEPTED");
            payload.put("bookingId", booking.getBookingId().toString());
            payload.put("workerName", workerName);
            payload.put("workerPhone", booking.getWorker() != null && booking.getWorker().getUser() != null
                    ? booking.getWorker().getUser().getPhoneNumber() : "");
            payload.put("pinCode", booking.getPinCode());
            payload.put("status", booking.getStatus().name());
            messagingTemplate.convertAndSend("/topic/booking/" + booking.getBookingId(), payload);
        } catch (Exception e) {
            logger.debug("WebSocket broadcast skipped: {}", e.getMessage());
        }
    }

    public void notifyBookingConfirmed(Booking booking) {
        if (booking.getUser() == null) return;
        String timeStr = booking.getScheduledAt() != null
                ? DATE_TIME_FORMATTER.format(booking.getScheduledAt().atZone(IST_ZONE))
                : "soon";

        String title = "Booking Confirmed!";
        String body = "Your scheduled service is confirmed for " + timeStr + ". Verification PIN: " + booking.getPinCode();

        fcmNotificationService.sendPushNotification(booking.getUser().getUserId(), title, body);

        if (booking.getWorker() != null && booking.getWorker().getUser() != null) {
            fcmNotificationService.sendPushNotification(
                    booking.getWorker().getUser().getUserId(),
                    "Scheduled Booking Confirmed",
                    "You have a confirmed job scheduled for " + timeStr + "."
            );
        }
    }

    public void notifyWorkerRejected(Worker worker, Booking booking) {
        if (worker == null || worker.getUser() == null) return;
        fcmNotificationService.sendPushNotification(
                worker.getUser().getUserId(),
                "Job Rejected",
                "Job request rejected. Account temporarily locked for 1 hour."
        );
    }

    public void notifyUpcomingBookingReminder(Booking booking) {
        if (booking.getUser() == null) return;
        String timeStr = booking.getScheduledAt() != null
                ? DATE_TIME_FORMATTER.format(booking.getScheduledAt().atZone(IST_ZONE))
                : "shortly";

        fcmNotificationService.sendPushNotification(
                booking.getUser().getUserId(),
                "Upcoming Service Reminder",
                "Your scheduled service is coming up at " + timeStr + "."
        );

        if (booking.getWorker() != null && booking.getWorker().getUser() != null) {
            fcmNotificationService.sendPushNotification(
                    booking.getWorker().getUser().getUserId(),
                    "Upcoming Job Reminder",
                    "Your scheduled job starts at " + timeStr + ". Please arrive on time."
            );
        }
    }

    public void notifyBookingCancelled(Booking booking, String reason, String cancelledByRole) {
        String title = "Booking Cancelled";
        String body = "Booking #" + booking.getBookingId().toString().substring(0, 8) +
                " was cancelled by " + cancelledByRole + ". Reason: " + (reason != null ? reason : "Not specified");

        if (booking.getUser() != null) {
            fcmNotificationService.sendPushNotification(booking.getUser().getUserId(), title, body);
        }
        if (booking.getWorker() != null && booking.getWorker().getUser() != null) {
            fcmNotificationService.sendPushNotification(booking.getWorker().getUser().getUserId(), title, body);
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "BOOKING_CANCELLED");
            payload.put("bookingId", booking.getBookingId().toString());
            payload.put("status", "CANCELLED");
            payload.put("reason", reason);
            messagingTemplate.convertAndSend("/topic/booking/" + booking.getBookingId(), payload);
        } catch (Exception e) {
            logger.debug("WebSocket broadcast skipped: {}", e.getMessage());
        }
    }

    public void notifyBookingRescheduled(Booking booking, Instant oldScheduledAt, String reason) {
        String newTimeStr = booking.getScheduledAt() != null
                ? DATE_TIME_FORMATTER.format(booking.getScheduledAt().atZone(IST_ZONE))
                : "updated time";

        String title = "Booking Rescheduled";
        String body = "Your booking #" + booking.getBookingId().toString().substring(0, 8) +
                " has been rescheduled to " + newTimeStr + ". Reason: " + (reason != null ? reason : "Schedule adjustment");

        if (booking.getUser() != null) {
            fcmNotificationService.sendPushNotification(booking.getUser().getUserId(), title, body);
        }
        if (booking.getWorker() != null && booking.getWorker().getUser() != null) {
            fcmNotificationService.sendPushNotification(
                    booking.getWorker().getUser().getUserId(),
                    "Job Rescheduled",
                    "Assigned booking #" + booking.getBookingId().toString().substring(0, 8) + " moved to " + newTimeStr
            );
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "BOOKING_RESCHEDULED");
            payload.put("bookingId", booking.getBookingId().toString());
            payload.put("newScheduledAt", booking.getScheduledAt().toString());
            payload.put("reason", reason);
            messagingTemplate.convertAndSend("/topic/booking/" + booking.getBookingId(), payload);
        } catch (Exception e) {
            logger.debug("WebSocket broadcast skipped: {}", e.getMessage());
        }
    }

    public void notifyWorkerReassigned(Booking booking, Worker oldWorker, String reason) {
        String workerName = booking.getWorker() != null && booking.getWorker().getUser() != null
                ? booking.getWorker().getUser().getFullName() : "a new service professional";

        String title = "Service Partner Updated";
        String body = "Your booking has been reassigned to " + workerName + ". Reason: " + (reason != null ? reason : "Partner reassignment");

        if (booking.getUser() != null) {
            fcmNotificationService.sendPushNotification(booking.getUser().getUserId(), title, body);
        }
        if (oldWorker != null && oldWorker.getUser() != null) {
            fcmNotificationService.sendPushNotification(
                    oldWorker.getUser().getUserId(),
                    "Job Reassigned",
                    "Booking #" + booking.getBookingId().toString().substring(0, 8) + " has been reassigned to another partner."
            );
        }
        if (booking.getWorker() != null && booking.getWorker().getUser() != null) {
            fcmNotificationService.sendPushNotification(
                    booking.getWorker().getUser().getUserId(),
                    "New Job Assigned",
                    "You have been assigned to booking #" + booking.getBookingId().toString().substring(0, 8) + "."
            );
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "WORKER_REASSIGNED");
            payload.put("bookingId", booking.getBookingId().toString());
            payload.put("workerId", booking.getWorker() != null ? booking.getWorker().getWorkerId().toString() : null);
            payload.put("workerName", workerName);
            messagingTemplate.convertAndSend("/topic/booking/" + booking.getBookingId(), payload);
        } catch (Exception e) {
            logger.debug("WebSocket broadcast skipped: {}", e.getMessage());
        }
    }
}

