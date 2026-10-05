package com.homeease.backend.service;

import com.homeease.backend.dto.AdminDto.*;
import com.homeease.backend.dto.CatalogDto.AdminDashboardStats;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AdminService {

    private final UserRepository userRepository;
    private final WorkerRepository workerRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final NotificationRepository notificationRepository;
    private final FcmNotificationService fcmNotificationService;

    public AdminService(UserRepository userRepository,
                        WorkerRepository workerRepository,
                        BookingRepository bookingRepository,
                        PaymentRepository paymentRepository,
                        NotificationRepository notificationRepository,
                        FcmNotificationService fcmNotificationService) {
        this.userRepository = userRepository;
        this.workerRepository = workerRepository;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.notificationRepository = notificationRepository;
        this.fcmNotificationService = fcmNotificationService;
    }

    @Transactional(readOnly = true)
    public AdminDashboardStats getDashboardStats() {
        long totalUsers = userRepository.countByRole(UserRole.CUSTOMER);
        long totalWorkers = workerRepository.count();
        long activeWorkers = workerRepository.findAll().stream().filter(w -> Boolean.TRUE.equals(w.getIsOnline())).count();
        long totalBookings = bookingRepository.count();

        var payments = paymentRepository.findAll().stream()
                .filter(p -> p.getStatus() == TransactionState.SUCCESS)
                .toList();

        BigDecimal grossRevenue = BigDecimal.ZERO;
        BigDecimal platformCommission = BigDecimal.ZERO;

        for (var p : payments) {
            grossRevenue = grossRevenue.add(p.getGrossAmount());
            platformCommission = platformCommission.add(p.getPlatformCommissionAmount());
        }

        return AdminDashboardStats.builder()
                .totalUsersCount(totalUsers)
                .totalWorkersCount(totalWorkers)
                .activeWorkersCount(activeWorkers)
                .totalBookingsCount(totalBookings)
                .completedBookingsCount(payments.size())
                .totalRevenue(grossRevenue)
                .totalPlatformCommission(platformCommission)
                .build();
    }

    // --- Worker Governance APIs ---
    @Transactional
    public Worker verifyWorkerKyc(UUID workerId) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new RuntimeException("Worker not found"));
        worker.setIsVerified(true);
        return workerRepository.save(worker);
    }

    @Transactional
    public Worker blockWorker(UUID workerId, Integer blockHours) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new RuntimeException("Worker not found"));
        worker.setBlockedUntil(Instant.now().plus(blockHours != null ? blockHours : 24, ChronoUnit.HOURS));
        worker.setIsOnline(false);
        return workerRepository.save(worker);
    }

    @Transactional
    public Worker unblockWorker(UUID workerId) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new RuntimeException("Worker not found"));
        worker.setBlockedUntil(null);
        return workerRepository.save(worker);
    }

    @Transactional(readOnly = true)
    public List<Worker> getAllWorkers() {
        return workerRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Worker getWorkerById(UUID workerId) {
        return workerRepository.findById(workerId)
                .orElseThrow(() -> new RuntimeException("Worker not found"));
    }

    // --- Payments Management APIs ---
    @Transactional(readOnly = true)
    public List<PaymentResponse> getAllPayments() {
        return paymentRepository.findAll().stream()
                .map(this::mapPaymentToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));
        return mapPaymentToResponse(payment);
    }

    @Transactional
    public PaymentResponse updatePaymentStatus(UUID paymentId, TransactionState newStatus) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found"));
        payment.setStatus(newStatus);
        if (payment.getBooking() != null) {
            payment.getBooking().setPaymentStatus(newStatus);
        }
        Payment updated = paymentRepository.save(payment);
        return mapPaymentToResponse(updated);
    }

    // --- User Management APIs ---
    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers(UserRole roleFilter) {
        List<User> users = (roleFilter != null) ? userRepository.findByRole(roleFilter) : userRepository.findAll();
        return users.stream().map(this::mapUserToResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return mapUserToResponse(user);
    }

    @Transactional
    public UserResponse updateUserRole(UUID userId, UserRole newRole) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setRole(newRole);
        User saved = userRepository.save(user);
        return mapUserToResponse(saved);
    }

    // --- Live Location Tracking APIs ---
    @Transactional(readOnly = true)
    public List<LiveWorkerLocationResponse> getLiveWorkerLocations() {
        return workerRepository.findAll().stream()
                .filter(w -> Boolean.TRUE.equals(w.getIsOnline()))
                .map(w -> LiveWorkerLocationResponse.builder()
                        .workerId(w.getWorkerId())
                        .userId(w.getUser().getUserId())
                        .fullName(w.getUser().getFullName())
                        .phoneNumber(w.getUser().getPhoneNumber())
                        .isOnline(w.getIsOnline())
                        .isVerified(w.getIsVerified())
                        .currentLat(w.getCurrentLat())
                        .currentLng(w.getCurrentLng())
                        .lastUpdated(Instant.now())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LiveCustomerLocationResponse> getLiveCustomerLocations() {
        return bookingRepository.findAll().stream()
                .filter(b -> b.getStatus() != BookingStage.COMPLETED && b.getStatus() != BookingStage.CANCELLED)
                .map(b -> LiveCustomerLocationResponse.builder()
                        .bookingId(b.getBookingId())
                        .userId(b.getUser().getUserId())
                        .customerName(b.getUser().getFullName())
                        .customerPhone(b.getUser().getPhoneNumber())
                        .serviceName(b.getService() != null ? b.getService().getName() : "Home Service")
                        .bookingStatus(b.getStatus())
                        .userLat(b.getUserLat())
                        .userLng(b.getUserLng())
                        .assignedWorkerId(b.getWorker() != null ? b.getWorker().getWorkerId() : null)
                        .assignedWorkerName(b.getWorker() != null ? b.getWorker().getUser().getFullName() : null)
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public LiveMapOverviewResponse getLiveMapOverview() {
        List<LiveWorkerLocationResponse> workers = getLiveWorkerLocations();
        List<LiveCustomerLocationResponse> customers = getLiveCustomerLocations();
        return LiveMapOverviewResponse.builder()
                .activeWorkers(workers)
                .activeBookings(customers)
                .totalOnlineWorkers(workers.size())
                .totalActiveBookings(customers.size())
                .build();
    }

    // --- Notifications Dispatch & Scheduling APIs ---
    @Transactional
    public String sendInstantNotification(InstantNotificationRequest request) {
        if (request.getRecipientUserId() != null) {
            fcmNotificationService.sendPushNotification(request.getRecipientUserId(), request.getTitle(), request.getBody());
            return "Instant push notification dispatched to user " + request.getRecipientUserId();
        } else {
            fcmNotificationService.sendPushNotification(UUID.randomUUID(), request.getTitle(), request.getBody());
            return "Broadcast push notification dispatched to target topic.";
        }
    }

    @Transactional
    public NotificationResponse scheduleNotification(ScheduledNotificationRequest request) {
        User user = null;
        if (request.getRecipientUserId() != null) {
            user = userRepository.findById(request.getRecipientUserId()).orElse(null);
        }

        Worker worker = null;
        if (request.getRecipientWorkerId() != null) {
            worker = workerRepository.findById(request.getRecipientWorkerId()).orElse(null);
        }

        Notification notification = Notification.builder()
                .user(user)
                .worker(worker)
                .title(request.getTitle())
                .message(request.getMessage())
                .type(request.getType() != null ? request.getType() : "PUSH")
                .scheduledAt(request.getScheduledAt())
                .status(NotifyStatus.PENDING)
                .isRead(false)
                .build();

        Notification saved = notificationRepository.save(notification);
        return mapNotificationToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getAllNotifications() {
        return notificationRepository.findAll().stream()
                .map(this::mapNotificationToResponse)
                .collect(Collectors.toList());
    }

    // --- Helper Mappers ---
    private PaymentResponse mapPaymentToResponse(Payment p) {
        return PaymentResponse.builder()
                .paymentId(p.getPaymentId())
                .bookingId(p.getBooking() != null ? p.getBooking().getBookingId() : null)
                .customerName(p.getBooking() != null && p.getBooking().getUser() != null ? p.getBooking().getUser().getFullName() : "N/A")
                .customerPhone(p.getBooking() != null && p.getBooking().getUser() != null ? p.getBooking().getUser().getPhoneNumber() : "N/A")
                .transactionRef(p.getTransactionRef())
                .grossAmount(p.getGrossAmount())
                .platformCommissionPercent(p.getPlatformCommissionPercent())
                .platformCommissionAmount(p.getPlatformCommissionAmount())
                .workerPayoutAmount(p.getWorkerPayoutAmount())
                .status(p.getStatus())
                .createdAt(p.getCreatedAt())
                .build();
    }

    private UserResponse mapUserToResponse(User u) {
        Worker w = workerRepository.findByUser_UserId(u.getUserId()).orElse(null);
        return UserResponse.builder()
                .userId(u.getUserId())
                .fullName(u.getFullName())
                .phoneNumber(u.getPhoneNumber())
                .email(u.getEmail())
                .role(u.getRole())
                .createdAt(u.getCreatedAt())
                .isWorker(w != null)
                .isWorkerVerified(w != null ? w.getIsVerified() : false)
                .isWorkerBlocked(w != null && w.getBlockedUntil() != null && w.getBlockedUntil().isAfter(Instant.now()))
                .build();
    }

    private NotificationResponse mapNotificationToResponse(Notification n) {
        return NotificationResponse.builder()
                .notificationId(n.getNotificationId())
                .userId(n.getUser() != null ? n.getUser().getUserId() : null)
                .recipientName(n.getUser() != null ? n.getUser().getFullName() : (n.getWorker() != null ? n.getWorker().getUser().getFullName() : "Broadcast"))
                .workerId(n.getWorker() != null ? n.getWorker().getWorkerId() : null)
                .title(n.getTitle())
                .message(n.getMessage())
                .type(n.getType())
                .scheduledAt(n.getScheduledAt())
                .sentAt(n.getSentAt())
                .status(n.getStatus())
                .isRead(n.getIsRead())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
