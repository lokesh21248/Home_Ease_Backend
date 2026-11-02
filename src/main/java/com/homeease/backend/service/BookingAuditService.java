package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.AdminCalendarDto.AuditLogDto;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.BookingAuditLog;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.repository.BookingAuditLogRepository;
import com.homeease.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BookingAuditService {

    private static final Logger logger = LoggerFactory.getLogger(BookingAuditService.class);

    private final BookingAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public BookingAuditService(BookingAuditLogRepository auditLogRepository,
                               UserRepository userRepository,
                               ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BookingAuditLog recordLog(Booking booking,
                                     String entityType,
                                     String entityId,
                                     UUID adminId,
                                     String action,
                                     Object previousValue,
                                     Object updatedValue,
                                     String reason) {
        String adminName = "SYSTEM_ADMIN";
        User admin = null;
        if (adminId != null) {
            admin = userRepository.findById(adminId).orElse(null);
            if (admin != null && admin.getFullName() != null) {
                adminName = admin.getFullName();
            }
        }

        String prevStr = serialize(previousValue);
        String updatedStr = serialize(updatedValue);

        BookingAuditLog log = BookingAuditLog.builder()
                .booking(booking)
                .entityType(entityType != null ? entityType : "BOOKING")
                .entityId(entityId != null ? entityId : (booking != null ? booking.getBookingId().toString() : null))
                .admin(admin)
                .adminName(adminName)
                .action(action)
                .previousValue(prevStr)
                .updatedValue(updatedStr)
                .reason(reason)
                .build();

        log = auditLogRepository.save(log);
        logger.info("[AUDIT] Action: {}, Entity: {}:{}, Admin: {}, Reason: {}",
                action, entityType, entityId, adminName, reason);
        return log;
    }

    @Transactional(readOnly = true)
    public Page<AuditLogDto> getAuditLogs(UUID bookingId, String entityType, Pageable pageable) {
        Page<BookingAuditLog> page;
        if (bookingId != null) {
            page = auditLogRepository.findByBooking_BookingIdOrderByCreatedAtDesc(bookingId, pageable);
        } else if (entityType != null && !entityType.isBlank()) {
            page = auditLogRepository.findByEntityTypeOrderByCreatedAtDesc(entityType.trim(), pageable);
        } else {
            page = auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        return page.map(log -> AuditLogDto.builder()
                .logId(log.getLogId())
                .bookingId(log.getBooking() != null ? log.getBooking().getBookingId() : null)
                .entityType(log.getEntityType())
                .entityId(log.getEntityId())
                .adminId(log.getAdmin() != null ? log.getAdmin().getUserId() : null)
                .adminName(log.getAdminName())
                .action(log.getAction())
                .previousValue(log.getPreviousValue())
                .updatedValue(log.getUpdatedValue())
                .reason(log.getReason())
                .createdAt(log.getCreatedAt())
                .build());
    }

    private String serialize(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}

