package com.homeease.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.SlotHoldDto.*;
import com.homeease.backend.model.entity.BookingSlot;
import com.homeease.backend.model.entity.SlotWindowCapacity;
import com.homeease.backend.repository.BookingSlotRepository;
import com.homeease.backend.repository.SlotWindowCapacityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class SlotHoldService {

    private static final Logger logger = LoggerFactory.getLogger(SlotHoldService.class);
    private static final Duration HOLD_DURATION = Duration.ofMinutes(10);
    private static final long REDIS_CACHE_TTL_SECONDS = 45;

    private final BookingSlotRepository slotRepository;
    private final SlotWindowCapacityRepository capacityRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public SlotHoldService(BookingSlotRepository slotRepository,
                           SlotWindowCapacityRepository capacityRepository,
                           RedisTemplate<String, String> redisTemplate,
                           ObjectMapper objectMapper) {
        this.slotRepository = slotRepository;
        this.capacityRepository = capacityRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Atomically holds a capacity slot for a given window using Postgres pg_advisory_xact_lock.
     * Complies with Non-Negotiable Rule #3: pg_advisory_xact_lock (transaction-level).
     */
    @Transactional
    public HoldResponse holdSlot(HoldRequest request) {
        LocalTime startTime = parseStartTime(request.getSlotTime());
        LocalTime endTime = startTime.plusHours(1);
        int dayOfWeek = request.getDate().getDayOfWeek().getValue();
        String pincode = request.getPincode() != null ? request.getPincode().trim() : "500072";

        String windowKey = String.format("%s:%s:%s:%s",
                request.getServiceId(), pincode, request.getDate(), startTime);

        // 1. Transaction-level advisory lock to serialize capacity evaluation for this specific window
        // pg_advisory_xact_lock releases automatically when the transaction completes.
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:lockKey))")
                .setParameter("lockKey", windowKey)
                .getSingleResult();

        Instant now = Instant.now();

        // 2. Fetch configured window capacity (default to 3 if not explicitly seeded)
        int maxCapacity = 3;
        Optional<SlotWindowCapacity> capOpt = capacityRepository.findSpecificWindow(
                request.getServiceId(), pincode, dayOfWeek, startTime);
        if (capOpt.isPresent()) {
            maxCapacity = capOpt.get().getMaxCapacity();
        }

        // 3. Count active holds and confirmed bookings in this window
        long activeCount = slotRepository.countActiveHoldsInWindow(windowKey, now);

        if (activeCount >= maxCapacity) {
            logger.warn("Slot capacity exceeded for window {}. Current: {}/{}. Request rejected.",
                    windowKey, activeCount, maxCapacity);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    String.format("Slot capacity reached for window %s (%d/%d)", request.getSlotTime(), activeCount, maxCapacity));
        }

        // 4. Reserve capacity slot with 10-minute hold
        String lockToken = UUID.randomUUID().toString();
        Instant lockedUntil = now.plus(HOLD_DURATION);
        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        Instant startTimestamp = request.getDate().atTime(startTime).atZone(istZone).toInstant();
        Instant endTimestamp = request.getDate().atTime(endTime).atZone(istZone).toInstant();

        BookingSlot slot = BookingSlot.builder()
                .slotDate(request.getDate())
                .startTime(startTime)
                .endTime(endTime)
                .startTimestamp(startTimestamp)
                .endTimestamp(endTimestamp)
                .status("RESERVED")
                .windowKey(windowKey)
                .holdToken(lockToken)
                .lockToken(lockToken)
                .lockedUntil(lockedUntil)
                .build();

        slot = slotRepository.save(slot);

        // 5. Invalidate cached availability in Redis for this service/date/pincode
        invalidateRedisSlotCache(request.getServiceId(), pincode, request.getDate());

        int remaining = Math.max(0, maxCapacity - (int)(activeCount + 1));
        logger.info("Successfully acquired slot hold: holdId={}, window={}, remainingCapacity={}",
                slot.getSlotId(), windowKey, remaining);

        return HoldResponse.builder()
                .holdId(slot.getSlotId())
                .lockToken(lockToken)
                .windowKey(windowKey)
                .serviceId(request.getServiceId())
                .slotDate(request.getDate())
                .startTime(startTime.toString())
                .endTime(endTime.toString())
                .expiresAt(lockedUntil)
                .remainingCapacity(remaining)
                .message("Slot hold confirmed for 10 minutes.")
                .build();
    }

    /**
     * Release a hold explicitly (e.g. user navigated away or cleared selection).
     */
    @Transactional
    public void releaseHold(UUID holdId, String lockToken) {
        Optional<BookingSlot> slotOpt = slotRepository.findById(holdId);
        if (slotOpt.isPresent()) {
            BookingSlot slot = slotOpt.get();
            if (lockToken.equals(slot.getLockToken()) && "RESERVED".equals(slot.getStatus())) {
                slot.setStatus("CANCELLED");
                slotRepository.save(slot);
                if (slot.getWindowKey() != null) {
                    invalidateRedisSlotCacheByWindowKey(slot.getWindowKey());
                }
                logger.info("Released slot hold: {}", holdId);
            }
        }
    }

    /**
     * Get available slots for date with 30-60s Redis caching.
     */
    public List<SlotWindow> getSlots(UUID serviceId, String pincode, LocalDate date) {
        String cleanPincode = (pincode != null && !pincode.isBlank()) ? pincode.trim() : "500072";
        String cacheKey = String.format("cache:slots:%s:%s:%s", serviceId, cleanPincode, date);

        // Check Redis cache first
        try {
            String cachedJson = redisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null && !cachedJson.isBlank()) {
                return objectMapper.readValue(cachedJson, new TypeReference<List<SlotWindow>>() {});
            }
        } catch (Exception e) {
            logger.warn("Redis slot cache read error: {}", e.getMessage());
        }

        // Compute from DB
        List<SlotWindow> windows = computeAvailableWindows(serviceId, cleanPincode, date);

        // Store in Redis with 45-second TTL
        try {
            String json = objectMapper.writeValueAsString(windows);
            redisTemplate.opsForValue().set(cacheKey, json, REDIS_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Redis slot cache write error: {}", e.getMessage());
        }

        return windows;
    }

    private List<SlotWindow> computeAvailableWindows(UUID serviceId, String pincode, LocalDate date) {
        int dayOfWeek = date.getDayOfWeek().getValue();
        List<SlotWindowCapacity> configs = capacityRepository.findActiveWindows(serviceId, pincode, dayOfWeek);

        if (configs.isEmpty()) {
            // Standard fallback windows (09:00 to 19:00) with capacity 3
            configs = generateDefaultConfigs(serviceId, pincode, dayOfWeek);
        }

        Instant now = Instant.now();
        List<SlotWindow> result = new ArrayList<>();

        for (SlotWindowCapacity cfg : configs) {
            String windowKey = String.format("%s:%s:%s:%s", serviceId, pincode, date, cfg.getStartTime());
            long active = slotRepository.countActiveHoldsInWindow(windowKey, now);
            int remaining = Math.max(0, cfg.getMaxCapacity() - (int) active);

            result.add(SlotWindow.builder()
                    .windowKey(windowKey)
                    .timeSlot(String.format("%s - %s", cfg.getStartTime(), cfg.getEndTime()))
                    .startTime(cfg.getStartTime().toString())
                    .endTime(cfg.getEndTime().toString())
                    .maxCapacity(cfg.getMaxCapacity())
                    .availableCapacity(remaining)
                    .available(remaining > 0)
                    .build());
        }

        return result;
    }

    private List<SlotWindowCapacity> generateDefaultConfigs(UUID serviceId, String pincode, int dayOfWeek) {
        List<SlotWindowCapacity> list = new ArrayList<>();
        LocalTime[] starts = {
                LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0),
                LocalTime.of(12, 0), LocalTime.of(14, 0), LocalTime.of(15, 0),
                LocalTime.of(16, 0), LocalTime.of(17, 0), LocalTime.of(18, 0)
        };
        for (LocalTime st : starts) {
            list.add(SlotWindowCapacity.builder()
                    .zonePincode(pincode)
                    .dayOfWeek(dayOfWeek)
                    .startTime(st)
                    .endTime(st.plusHours(1))
                    .maxCapacity(3)
                    .active(true)
                    .build());
        }
        return list;
    }

    /**
     * Background Sweeper running every 30 seconds to expire abandoned holds.
     * Complies with Phase 1 requirement: Sweeper every 30s expires holds.
     */
    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void expireAbandonedHolds() {
        Instant now = Instant.now();
        List<BookingSlot> expired = slotRepository.findExpiredHolds(now);
        if (!expired.isEmpty()) {
            logger.info("SlotHoldSweeper: Expiring {} abandoned holds", expired.size());
            Set<String> affectedWindows = new HashSet<>();
            for (BookingSlot slot : expired) {
                slot.setStatus("EXPIRED");
                slotRepository.save(slot);
                if (slot.getWindowKey() != null) {
                    affectedWindows.add(slot.getWindowKey());
                }
            }
            // Invalidate Redis cache for all affected windows
            for (String key : affectedWindows) {
                invalidateRedisSlotCacheByWindowKey(key);
            }
        }
    }

    private void invalidateRedisSlotCache(UUID serviceId, String pincode, LocalDate date) {
        try {
            String cacheKey = String.format("cache:slots:%s:%s:%s", serviceId, pincode, date);
            redisTemplate.delete(cacheKey);
        } catch (Exception ignored) {
        }
    }

    private void invalidateRedisSlotCacheByWindowKey(String windowKey) {
        try {
            // windowKey is {serviceId}:{pincode}:{date}:{startTime}
            String[] parts = windowKey.split(":");
            if (parts.length >= 3) {
                String cacheKey = String.format("cache:slots:%s:%s:%s", parts[0], parts[1], parts[2]);
                redisTemplate.delete(cacheKey);
            }
        } catch (Exception ignored) {
        }
    }

    private LocalTime parseStartTime(String slotTime) {
        if (slotTime == null || slotTime.isBlank()) {
            return LocalTime.of(10, 0);
        }
        try {
            String timePart = slotTime.split("[-–]")[0].trim();
            if (timePart.length() == 4 && timePart.charAt(1) == ':') {
                timePart = "0" + timePart;
            }
            if (timePart.length() == 5) {
                return LocalTime.parse(timePart);
            }
            if (timePart.toUpperCase().contains("AM") || timePart.toUpperCase().contains("PM")) {
                int hour = Integer.parseInt(timePart.replaceAll("[^0-9]", ""));
                if (timePart.toUpperCase().contains("PM") && hour < 12) hour += 12;
                if (timePart.toUpperCase().contains("AM") && hour == 12) hour = 0;
                return LocalTime.of(hour, 0);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse slot time '{}', defaulting to 10:00", slotTime);
        }
        return LocalTime.of(10, 0);
    }
}

