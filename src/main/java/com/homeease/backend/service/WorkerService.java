package com.homeease.backend.service;

import com.homeease.backend.dto.WorkerDto.*;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.TransactionState;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.*;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class WorkerService {

    private final WorkerRepository workerRepository;
    private final UserRepository userRepository;
    private final SubServiceRepository subServiceRepository;
    private final PaymentRepository paymentRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public WorkerService(WorkerRepository workerRepository,
                         UserRepository userRepository,
                         SubServiceRepository subServiceRepository,
                         PaymentRepository paymentRepository,
                         RedisTemplate<String, String> redisTemplate) {
        this.workerRepository = workerRepository;
        this.userRepository = userRepository;
        this.subServiceRepository = subServiceRepository;
        this.paymentRepository = paymentRepository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public Worker registerKyc(UUID userId, KycRegisterRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

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
                        .isVerified(false)
                        .isOnline(false)
                        .build());

        worker.setAddress(request.getAddress());
        worker.setPanNumber(request.getPanNumber());
        worker.setPanDocUrl(request.getPanDocUrl());
        worker.setAadhaarDocUrl(request.getAadhaarDocUrl());
        worker.setBankAccountNo(request.getBankAccountNo());
        worker.setBankIfsc(request.getBankIfsc());

        return workerRepository.save(worker);
    }

    @Transactional
    public Worker toggleOnlineStatus(UUID userId, Boolean isOnline) {
        Worker worker = workerRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new RuntimeException("Worker profile not found. Please complete KYC registration."));

        if (!Boolean.TRUE.equals(worker.getIsVerified())) {
            throw new RuntimeException("Worker KYC account is pending verification by governance admin.");
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

        return worker;
    }

    @Transactional
    public void updateLiveLocation(UUID userId, Double lat, Double lng) {
        Worker worker = workerRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new RuntimeException("Worker profile not found"));

        worker.setCurrentLat(lat);
        worker.setCurrentLng(lng);
        Point point = geometryFactory.createPoint(new Coordinate(lng, lat));
        worker.setLocation(point);

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
                .orElseThrow(() -> new RuntimeException("Worker profile not found"));

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
}
