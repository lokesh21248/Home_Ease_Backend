package com.homeease.backend.service;

import com.homeease.backend.dto.WorkerDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.exception.UserNotFoundException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.TransactionState;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class WorkerService {

    private static final Logger logger = LoggerFactory.getLogger(WorkerService.class);

    private final WorkerRepository workerRepository;
    private final UserRepository userRepository;
    private final SubServiceRepository subServiceRepository;
    private final WorkerServiceLinkRepository workerServiceLinkRepository;
    private final PaymentRepository paymentRepository;
    private final ReviewRepository reviewRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${homeease.worker.auto-verify:false}")
    private boolean autoVerifyWorker;

    public WorkerService(WorkerRepository workerRepository,
                         UserRepository userRepository,
                         SubServiceRepository subServiceRepository,
                         WorkerServiceLinkRepository workerServiceLinkRepository,
                         PaymentRepository paymentRepository,
                         ReviewRepository reviewRepository,
                         RedisTemplate<String, String> redisTemplate) {
        this.workerRepository = workerRepository;
        this.userRepository = userRepository;
        this.subServiceRepository = subServiceRepository;
        this.workerServiceLinkRepository = workerServiceLinkRepository;
        this.paymentRepository = paymentRepository;
        this.reviewRepository = reviewRepository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public KycSubmissionResponse registerKyc(UUID userId, KycRegisterRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("USER_NOT_FOUND: User not found for ID: " + userId));

        if (user.getRole() != UserRole.WORKER) {
            user.setRole(UserRole.WORKER);
            userRepository.save(user);
        }
        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName().trim());
            userRepository.save(user);
        }

        Worker worker = workerRepository.findByUser_UserId(userId)
                .orElseGet(() -> Worker.builder()
                        .user(user)
                        .isVerified(autoVerifyWorker)
                        .isOnline(false)
                        .kycStatus("PENDING")
                        .build());

        worker.setAddress(request.getAddress());
        worker.setPanNumber(request.getPanNumber());
        worker.setPanDocUrl(request.getPanDocUrl());
        worker.setAadhaarDocUrl(request.getAadhaarDocUrl());
        worker.setBankAccountNo(request.getBankAccountNo());
        worker.setBankIfsc(request.getBankIfsc());

        if (request.getAadhaarNumber() != null && !request.getAadhaarNumber().isBlank()) {
            worker.setAadhaarNumber(request.getAadhaarNumber());
        }
        if (request.getAvatarUrl() != null && !request.getAvatarUrl().isBlank()) {
            worker.setAvatarUrl(request.getAvatarUrl());
        }
        if (request.getGender() != null && !request.getGender().isBlank()) {
            worker.setGender(request.getGender());
        }
        if (request.getDob() != null) {
            worker.setDob(request.getDob());
        }
        if (request.getExperienceYears() != null) {
            worker.setExperienceYears(request.getExperienceYears());
        }

        worker.setKycStatus(autoVerifyWorker ? "APPROVED" : "PENDING");
        worker.setIsVerified(autoVerifyWorker);

        worker = workerRepository.save(worker);

        // Link Sub-Services
        if (request.getSubServiceIds() != null && !request.getSubServiceIds().isEmpty()) {
            try {
                workerServiceLinkRepository.deleteByWorkerId(worker.getWorkerId());
                for (String subServiceIdStr : request.getSubServiceIds()) {
                    if (subServiceIdStr == null || subServiceIdStr.isBlank()) continue;
                    try {
                        UUID subServiceId = UUID.fromString(subServiceIdStr.trim());
                        SubService subService = subServiceRepository.findById(subServiceId).orElse(null);
                        if (subService != null) {
                            WorkerServiceId linkId = WorkerServiceId.builder()
                                    .workerId(worker.getWorkerId())
                                    .subServiceId(subServiceId)
                                    .build();
                            WorkerServiceLink link = WorkerServiceLink.builder()
                                    .id(linkId)
                                    .worker(worker)
                                    .subService(subService)
                                    .build();
                            workerServiceLinkRepository.save(link);
                        }
                    } catch (IllegalArgumentException e) {
                        logger.warn("Invalid subServiceId format passed to KYC registration: {}", subServiceIdStr);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to link sub-services during KYC registration: {}", e.getMessage());
            }
        }

        return KycSubmissionResponse.builder()
                .workerId(worker.getWorkerId())
                .isVerified(worker.getIsVerified())
                .kycStatus(worker.getKycStatus())
                .build();
    }

    @Transactional(readOnly = true)
    public WorkerProfileResponse getWorkerProfile(UUID userId) {
        Optional<Worker> workerOpt = workerRepository.findByUser_UserId(userId);

        if (workerOpt.isPresent()) {
            return mapToProfileResponse(workerOpt.get());
        }

        // Check if User exists but has not completed KYC yet
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("USER_NOT_FOUND: User profile not found for ID: " + userId));

        // Return empty/initial profile for new worker
        return WorkerProfileResponse.builder()
                .workerId(null)
                .userId(user.getUserId())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .email(user.getEmail())
                .isOnline(false)
                .isVerified(false)
                .kycStatus("NOT_UPLOADED")
                .rating(5.0)
                .jobsCompleted(0)
                .subServiceIds(List.of())
                .build();
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

        List<String> subServiceIds = new ArrayList<>();
        if (worker.getWorkerId() != null) {
            try {
                List<WorkerServiceLink> links = workerServiceLinkRepository.findById_WorkerId(worker.getWorkerId());
                for (WorkerServiceLink link : links) {
                    if (link.getSubService() != null && link.getSubService().getSubServiceId() != null) {
                        subServiceIds.add(link.getSubService().getSubServiceId().toString());
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not load sub-service IDs for worker: {}", e.getMessage());
            }
        }

        return WorkerProfileResponse.builder()
                .workerId(worker.getWorkerId())
                .userId(u != null ? u.getUserId() : null)
                .user(userMap)
                .fullName(u != null ? u.getFullName() : "")
                .phoneNumber(u != null ? u.getPhoneNumber() : "")
                .email(u != null ? u.getEmail() : "")
                .address(worker.getAddress())
                .avatarUrl(worker.getAvatarUrl())
                .panNumber(worker.getPanNumber())
                .panDocUrl(worker.getPanDocUrl())
                .aadhaarNumber(worker.getAadhaarNumber())
                .aadhaarDocUrl(worker.getAadhaarDocUrl())
                .bankAccountNo(worker.getBankAccountNo())
                .bankIfsc(worker.getBankIfsc())
                .isOnline(worker.getIsOnline())
                .isVerified(worker.getIsVerified())
                .kycStatus(worker.getKycStatus() != null ? worker.getKycStatus() : "NOT_UPLOADED")
                .gender(worker.getGender())
                .dob(worker.getDob())
                .experienceYears(worker.getExperienceYears())
                .subServiceIds(subServiceIds)
                .currentLat(worker.getCurrentLat())
                .currentLng(worker.getCurrentLng())
                .rating(Math.round(rating * 10.0) / 10.0)
                .jobsCompleted((int) jobsCompleted)
                .build();
    }
}
