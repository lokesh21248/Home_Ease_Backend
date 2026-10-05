package com.homeease.backend.service;

import com.homeease.backend.dto.WorkerDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.Payment;
import com.homeease.backend.model.entity.Review;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.TransactionState;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.PaymentRepository;
import com.homeease.backend.repository.ReviewRepository;
import com.homeease.backend.repository.SubServiceRepository;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.repository.WorkerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkerService {

    private final WorkerRepository workerRepository;
    private final UserRepository userRepository;
    private final SubServiceRepository subServiceRepository;
    private final PaymentRepository paymentRepository;
    private final ReviewRepository reviewRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${homeease.worker.auto-verify:true}")
    private boolean autoVerifyWorker;

    public WorkerService(WorkerRepository workerRepository,
                         UserRepository userRepository,
                         SubServiceRepository subServiceRepository,
                         PaymentRepository paymentRepository,
                         ReviewRepository reviewRepository,
                         RedisTemplate<String, String> redisTemplate) {
        this.workerRepository = workerRepository;
        this.userRepository = userRepository;
        this.subServiceRepository = subServiceRepository;
        this.paymentRepository = paymentRepository;
        this.reviewRepository = reviewRepository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public WorkerProfileResponse registerKyc(UUID userId, KycRegisterRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found for ID: " + userId));

        if (user.getRole() != UserRole.WORKER) {
            user.setRole(UserRole.WORKER);
            userRepository.save(user);
        }

        Worker worker = workerRepository.findByUser_UserId(userId)
                .orElseGet(() -> Worker.builder()
                        .user(user)
                        .address(request.getAddress())
                        .panNumber(request.getPanNumber())
                        .panDocUrl(request.getPanDocUrl())
                        .aadhaarDocUrl(request.getAadhaarDocUrl())
                        .bankAccountNo(request.getBankAccountNo())
                        .bankIfsc(request.getBankIfsc())
                        .isVerified(autoVerifyWorker)
                        .isOnline(false)
                        .build());

        worker.setAddress(request.getAddress());
        worker.setPanNumber(request.getPanNumber());
        worker.setPanDocUrl(request.getPanDocUrl());
        worker.setAadhaarDocUrl(request.getAadhaarDocUrl());
        worker.setBankAccountNo(request.getBankAccountNo());
        worker.setBankIfsc(request.getBankIfsc());

        if (autoVerifyWorker) {
            worker.setIsVerified(true);
        }

        worker = workerRepository.save(worker);
        return mapToProfileResponse(worker);
    }

    @Transactional(readOnly = true)
    public WorkerProfileResponse getWorkerProfile(UUID userId) {
        Worker worker = workerRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker profile not found. Please register KYC first."));
        return mapToProfileResponse(worker);
    }

    @Transactional
    public WorkerProfileResponse toggleOnlineStatus(UUID userId, Boolean isOnline) {
        Worker worker = workerRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker profile not found. Please complete KYC registration."));

        if (!Boolean.TRUE.equals(worker.getIsVerified())) {
            if (autoVerifyWorker) {
                worker.setIsVerified(true);
            } else {
                throw new RuntimeException("Worker KYC account is pending verification by governance admin.");
            }
        }

        if (worker.getBlockedUntil() != null && worker.getBlockedUntil().isAfter(Instant.now())) {
            throw new RuntimeException("Account is temporarily blocked until " + worker.getBlockedUntil() + " due to job rejection penalty.");
        }

        worker.setIsOnline(isOnline);
        worker = workerRepository.save(worker);

        if (!isOnline && worker.getWorkerId() != null) {
            try {
                redisTemplate.opsForGeo().remove("active_workers_geo", worker.getWorkerId().toString());
            } catch (Exception ignored) {}
        }

        return mapToProfileResponse(worker);
    }

    @Transactional
    public void updateLiveLocation(UUID userId, Double lat, Double lng) {
        Worker worker = workerRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker profile not found for user ID: " + userId));

        worker.setCurrentLat(lat);
        worker.setCurrentLng(lng);

        workerRepository.save(worker);

        if (Boolean.TRUE.equals(worker.getIsOnline()) && Boolean.TRUE.equals(worker.getIsVerified())) {
            try {
                redisTemplate.opsForGeo().add("active_workers_geo", new org.springframework.data.geo.Point(lng, lat), worker.getWorkerId().toString());
            } catch (Exception ignored) {}
        }
    }

    @Transactional(readOnly = true)
    public EarningsResponse getWorkerEarnings(UUID userId) {
        Worker worker = workerRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker profile not found"));

        List<Payment> payments = paymentRepository.findAll().stream()
                .filter(p -> p.getBooking() != null && p.getBooking().getWorker() != null &&
                        p.getBooking().getWorker().getWorkerId().equals(worker.getWorkerId()) &&
                        p.getStatus() == TransactionState.SUCCESS)
                .toList();

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal netPayout = BigDecimal.ZERO;

        for (Payment p : payments) {
            gross = gross.add(p.getGrossAmount());
            commission = commission.add(p.getPlatformCommissionAmount());
            netPayout = netPayout.add(p.getWorkerPayoutAmount());
        }

        return EarningsResponse.builder()
                .totalEarnings(gross)
                .totalCommissionPaid(commission)
                .netPayout(netPayout)
                .completedBookingsCount(payments.size())
                .build();
    }

    public WorkerProfileResponse mapToProfileResponse(Worker worker) {
        User u = worker.getUser();
        Map<String, Object> userMap = new LinkedHashMap<>();
        if (u != null) {
            userMap.put("userId", u.getUserId());
            userMap.put("fullName", u.getFullName());
            userMap.put("phoneNumber", u.getPhoneNumber());
            userMap.put("email", u.getEmail());
            userMap.put("role", u.getRole() != null ? u.getRole().name() : "WORKER");
        }

        List<Review> reviews = worker.getWorkerId() != null
                ? reviewRepository.findByWorker_WorkerId(worker.getWorkerId())
                : List.of();
        double rating = reviews.isEmpty()
                ? 5.0
                : reviews.stream().mapToInt(Review::getRating).average().orElse(5.0);

        long jobsCompleted = 0;
        if (worker.getWorkerId() != null) {
            jobsCompleted = paymentRepository.findAll().stream()
                    .filter(p -> p.getBooking() != null && p.getBooking().getWorker() != null &&
                            p.getBooking().getWorker().getWorkerId().equals(worker.getWorkerId()) &&
                            p.getStatus() == TransactionState.SUCCESS)
                    .count();
        }

        return WorkerProfileResponse.builder()
                .workerId(worker.getWorkerId())
                .userId(u != null ? u.getUserId() : null)
                .user(userMap)
                .fullName(u != null ? u.getFullName() : "")
                .phoneNumber(u != null ? u.getPhoneNumber() : "")
                .email(u != null ? u.getEmail() : "")
                .address(worker.getAddress())
                .panNumber(worker.getPanNumber())
                .panDocUrl(worker.getPanDocUrl())
                .aadhaarDocUrl(worker.getAadhaarDocUrl())
                .bankAccountNo(worker.getBankAccountNo())
                .bankIfsc(worker.getBankIfsc())
                .isOnline(worker.getIsOnline())
                .isVerified(worker.getIsVerified())
                .currentLat(worker.getCurrentLat())
                .currentLng(worker.getCurrentLng())
                .rating(Math.round(rating * 10.0) / 10.0)
                .jobsCompleted((int) jobsCompleted)
                .build();
    }
}
