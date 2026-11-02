package com.homeease.backend.service;

import com.homeease.backend.dto.BookingDto.AvailableDateDto;
import com.homeease.backend.dto.BookingDto.AvailableSlotDto;
import com.homeease.backend.dto.WorkerDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class WorkerAvailabilityService {

    private static final Logger logger = LoggerFactory.getLogger(WorkerAvailabilityService.class);
    public static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm a");
    private static final DateTimeFormatter TIME_24_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private static final List<BookingStage> ACTIVE_STAGES = List.of(
            BookingStage.CONFIRMED,
            BookingStage.ACCEPTED,
            BookingStage.ON_THE_WAY,
            BookingStage.ARRIVED,
            BookingStage.IN_PROGRESS
    );

    private static final String[] DAY_NAMES = {
            "", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    };

    private final WorkerAvailabilityRepository availabilityRepository;
    private final WorkerTimeOffRepository timeOffRepository;
    private final BookingSlotRepository slotRepository;
    private final BookingRepository bookingRepository;
    private final WorkerRepository workerRepository;
    private final WorkerServiceLinkRepository workerServiceLinkRepository;
    private final BusinessWorkingHoursRepository workingHoursRepository;
    private final CalendarDateOverrideRepository overrideRepository;
    private final BookingSettingsRepository bookingSettingsRepository;
    private final org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkerAvailabilityService(WorkerAvailabilityRepository availabilityRepository,
                                     WorkerTimeOffRepository timeOffRepository,
                                     BookingSlotRepository slotRepository,
                                     BookingRepository bookingRepository,
                                     WorkerRepository workerRepository,
                                     WorkerServiceLinkRepository workerServiceLinkRepository,
                                     BusinessWorkingHoursRepository workingHoursRepository,
                                     CalendarDateOverrideRepository overrideRepository,
                                     BookingSettingsRepository bookingSettingsRepository,
                                     org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplate) {
        this.availabilityRepository = availabilityRepository;
        this.timeOffRepository = timeOffRepository;
        this.slotRepository = slotRepository;
        this.bookingRepository = bookingRepository;
        this.workerRepository = workerRepository;
        this.workerServiceLinkRepository = workerServiceLinkRepository;
        this.workingHoursRepository = workingHoursRepository;
        this.overrideRepository = overrideRepository;
        this.bookingSettingsRepository = bookingSettingsRepository;
        this.redisTemplate = redisTemplate;
    }

    // Overloaded constructor for unit test backward-compatibility
    public WorkerAvailabilityService(WorkerAvailabilityRepository availabilityRepository,
                                     WorkerTimeOffRepository timeOffRepository,
                                     BookingSlotRepository slotRepository,
                                     BookingRepository bookingRepository,
                                     WorkerRepository workerRepository,
                                     WorkerServiceLinkRepository workerServiceLinkRepository) {
        this(availabilityRepository, timeOffRepository, slotRepository, bookingRepository,
             workerRepository, workerServiceLinkRepository, null, null, null, null);
    }

    /**
     * Retrieve weekly availability for a worker.
     * If worker has no entries saved yet, returns default Monday-Saturday schedule.
     */
    @Transactional(readOnly = true)
    public List<DayScheduleDto> getWorkerAvailability(UUID workerId) {
        List<WorkerAvailability> existing = availabilityRepository.findByWorker_WorkerIdOrderByDayOfWeekAsc(workerId);
        Map<Integer, WorkerAvailability> byDay = new HashMap<>();
        for (WorkerAvailability wa : existing) {
            byDay.put(wa.getDayOfWeek(), wa);
        }

        List<DayScheduleDto> result = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            WorkerAvailability wa = byDay.get(day);
            if (wa != null) {
                result.add(DayScheduleDto.builder()
                        .dayOfWeek(day)
                        .dayName(DAY_NAMES[day])
                        .isAvailable(wa.getIsAvailable())
                        .startTime(wa.getStartTime().format(TIME_24_FORMATTER))
                        .endTime(wa.getEndTime().format(TIME_24_FORMATTER))
                        .breakStartTime(wa.getBreakStartTime() != null ? wa.getBreakStartTime().format(TIME_24_FORMATTER) : null)
                        .breakEndTime(wa.getBreakEndTime() != null ? wa.getBreakEndTime().format(TIME_24_FORMATTER) : null)
                        .slotDurationMins(wa.getSlotDurationMins())
                        .build());
            } else {
                // Default fallback: Mon-Sat available (08:00 - 20:00), Sun off
                boolean defaultAvailable = day <= 6;
                result.add(DayScheduleDto.builder()
                        .dayOfWeek(day)
                        .dayName(DAY_NAMES[day])
                        .isAvailable(defaultAvailable)
                        .startTime("08:00")
                        .endTime("20:00")
                        .breakStartTime("13:00")
                        .breakEndTime("14:00")
                        .slotDurationMins(60)
                        .build());
            }
        }
        return result;
    }

    /**
     * Persist weekly schedule changes.
     */
    @Transactional
    public List<DayScheduleDto> saveWorkerAvailability(UUID workerId, List<DayScheduleDto> scheduleList) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker not found: " + workerId));

        for (DayScheduleDto dto : scheduleList) {
            if (dto.getDayOfWeek() == null || dto.getDayOfWeek() < 1 || dto.getDayOfWeek() > 7) continue;

            LocalTime start = parseTime(dto.getStartTime(), LocalTime.of(8, 0));
            LocalTime end = parseTime(dto.getEndTime(), LocalTime.of(20, 0));
            LocalTime breakStart = parseTime(dto.getBreakStartTime(), LocalTime.of(13, 0));
            LocalTime breakEnd = parseTime(dto.getBreakEndTime(), LocalTime.of(14, 0));

            WorkerAvailability wa = availabilityRepository.findByWorker_WorkerIdAndDayOfWeek(workerId, dto.getDayOfWeek())
                    .orElseGet(() -> WorkerAvailability.builder()
                            .worker(worker)
                            .dayOfWeek(dto.getDayOfWeek())
                            .build());

            wa.setIsAvailable(Boolean.TRUE.equals(dto.getIsAvailable()));
            wa.setStartTime(start);
            wa.setEndTime(end);
            wa.setBreakStartTime(breakStart);
            wa.setBreakEndTime(breakEnd);
            wa.setSlotDurationMins(dto.getSlotDurationMins() != null ? dto.getSlotDurationMins() : 60);

            availabilityRepository.save(wa);
        }

        return getWorkerAvailability(workerId);
    }

    /**
     * Get blocked dates / time off.
     */
    @Transactional(readOnly = true)
    public List<TimeOffResponse> getWorkerTimeOff(UUID workerId) {
        return timeOffRepository.findByWorker_WorkerIdOrderByStartDateAsc(workerId).stream()
                .map(t -> TimeOffResponse.builder()
                        .timeOffId(t.getTimeOffId())
                        .startDate(t.getStartDate())
                        .endDate(t.getEndDate())
                        .reason(t.getReason())
                        .createdAt(t.getCreatedAt())
                        .build())
                .toList();
    }

    /**
     * Add blocked dates / vacation.
     */
    @Transactional
    public TimeOffResponse addWorkerTimeOff(UUID workerId, TimeOffRequest request) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker not found: " + workerId));

        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("End date cannot be before start date.");
        }

        WorkerTimeOff timeOff = WorkerTimeOff.builder()
                .worker(worker)
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .reason(request.getReason() != null ? request.getReason().trim() : "Time Off")
                .build();

        timeOff = timeOffRepository.save(timeOff);

        return TimeOffResponse.builder()
                .timeOffId(timeOff.getTimeOffId())
                .startDate(timeOff.getStartDate())
                .endDate(timeOff.getEndDate())
                .reason(timeOff.getReason())
                .createdAt(timeOff.getCreatedAt())
                .build();
    }

    /**
     * Delete blocked date.
     */
    @Transactional
    public void deleteWorkerTimeOff(UUID workerId, UUID timeOffId) {
        WorkerTimeOff timeOff = timeOffRepository.findById(timeOffId)
                .orElseThrow(() -> new ResourceNotFoundException("Time-off entry not found: " + timeOffId));
        if (!timeOff.getWorker().getWorkerId().equals(workerId)) {
            throw new SecurityException("Cannot delete time-off of another worker");
        }
        timeOffRepository.delete(timeOff);
    }

    public BookingSettings getEffectiveSettings(UUID serviceId) {
        if (bookingSettingsRepository == null) {
            return BookingSettings.builder()
                    .slotDurationMins(60)
                    .bufferDurationMins(15)
                    .advanceBookingDays(14)
                    .minBookingNoticeMins(30)
                    .maxBookingCapacityPerSlot(10)
                    .build();
        }
        if (serviceId != null) {
            Optional<BookingSettings> svcSetting = bookingSettingsRepository.findByService_ServiceId(serviceId);
            if (svcSetting.isPresent() && Boolean.TRUE.equals(svcSetting.get().getIsActive())) {
                return svcSetting.get();
            }
        }
        return bookingSettingsRepository.findByServiceIsNull()
                .orElseGet(() -> BookingSettings.builder()
                        .slotDurationMins(60)
                        .bufferDurationMins(15)
                        .advanceBookingDays(14)
                        .minBookingNoticeMins(30)
                        .maxBookingCapacityPerSlot(10)
                        .build());
    }

    public CalendarDateOverride getEffectiveDateOverride(UUID serviceId, LocalDate date) {
        if (overrideRepository == null) return null;
        List<CalendarDateOverride> overrides = overrideRepository.findApplicableOverrides(serviceId, date);
        return overrides.isEmpty() ? null : overrides.get(0);
    }

    public BusinessWorkingHours getEffectiveWorkingHours(UUID serviceId, int dayOfWeek) {
        if (workingHoursRepository == null) {
            return BusinessWorkingHours.builder()
                    .dayOfWeek(dayOfWeek)
                    .isOpen(dayOfWeek <= 6)
                    .openTime(LocalTime.of(8, 0))
                    .closeTime(LocalTime.of(20, 0))
                    .breakStartTime(LocalTime.of(13, 0))
                    .breakEndTime(LocalTime.of(14, 0))
                    .build();
        }
        if (serviceId != null) {
            Optional<BusinessWorkingHours> svcHours = workingHoursRepository.findByService_ServiceIdAndDayOfWeek(serviceId, dayOfWeek);
            if (svcHours.isPresent()) return svcHours.get();
        }
        return workingHoursRepository.findByServiceIsNullAndDayOfWeek(dayOfWeek)
                .orElseGet(() -> BusinessWorkingHours.builder()
                        .dayOfWeek(dayOfWeek)
                        .isOpen(dayOfWeek <= 6)
                        .openTime(LocalTime.of(8, 0))
                        .closeTime(LocalTime.of(20, 0))
                        .breakStartTime(LocalTime.of(13, 0))
                        .breakEndTime(LocalTime.of(14, 0))
                        .build());
    }

    public void invalidateCalendarCache() {
        if (redisTemplate != null) {
            try {
                Set<String> keys = redisTemplate.keys("calendar:*");
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            } catch (Exception e) {
                logger.warn("Could not invalidate Redis calendar cache: {}", e.getMessage());
            }
        }
    }

    /**
     * Dynamically fetch available dates for scheduled booking.
     * Aggregates eligible workers for the service/sub-service, scans next N days in Asia/Kolkata,
     * factors in admin date overrides, business working hours, and worker availability.
     */
    @Transactional(readOnly = true)
    public List<AvailableDateDto> getAvailableDates(UUID serviceId, UUID subServiceId, int daysAhead) {
        BookingSettings settings = getEffectiveSettings(serviceId);
        int maxDays = (daysAhead > 0 && daysAhead <= 30) ? daysAhead : settings.getAdvanceBookingDays();
        LocalDate today = LocalDate.now(IST_ZONE);

        List<AvailableDateDto> result = new ArrayList<>();

        for (int i = 0; i < maxDays; i++) {
            LocalDate date = today.plusDays(i);
            int dayOfWeek = date.getDayOfWeek().getValue(); // 1 = Monday ... 7 = Sunday

            // 1. Check Date Override (Blocked, Holiday)
            CalendarDateOverride override = getEffectiveDateOverride(serviceId, date);
            if (override != null && Boolean.TRUE.equals(override.getIsClosed())) {
                result.add(AvailableDateDto.builder()
                        .date(date.toString())
                        .dayName(date.getDayOfWeek().name().substring(0, 3))
                        .monthName(date.getMonth().name().substring(0, 3))
                        .dayOfMonth(date.getDayOfMonth())
                        .available(false)
                        .availableSlotsCount(0)
                        .build());
                continue;
            }

            // 2. Check Business Working Hours (Closed on this day)
            BusinessWorkingHours hours = getEffectiveWorkingHours(serviceId, dayOfWeek);
            if (hours != null && !Boolean.TRUE.equals(hours.getIsOpen())) {
                result.add(AvailableDateDto.builder()
                        .date(date.toString())
                        .dayName(date.getDayOfWeek().name().substring(0, 3))
                        .monthName(date.getMonth().name().substring(0, 3))
                        .dayOfMonth(date.getDayOfMonth())
                        .available(false)
                        .availableSlotsCount(0)
                        .build());
                continue;
            }

            // 3. Check open slots count for this date
            List<AvailableSlotDto> slots = getAvailableTimeSlots(date, serviceId, subServiceId);
            long availableSlotsCount = slots.stream().filter(s -> Boolean.TRUE.equals(s.getAvailable())).count();

            result.add(AvailableDateDto.builder()
                    .date(date.toString())
                    .dayName(date.getDayOfWeek().name().substring(0, 3))
                    .monthName(date.getMonth().name().substring(0, 3))
                    .dayOfMonth(date.getDayOfMonth())
                    .available(availableSlotsCount > 0)
                    .availableSlotsCount((int) availableSlotsCount)
                    .build());
        }

        return result;
    }

    /**
     * Dynamically calculates available time slots for a given date from actual worker schedules
     * and admin business configuration. Never returns hardcoded slots.
     * Validates:
     *  - Admin date overrides (holidays, special hours, blocked windows)
     *  - Business working hours & business lunch breaks
     *  - Worker weekly shift availability
     *  - Worker vacations/time-off
     *  - Worker overlapping bookings in database
     *  - Maximum booking capacity per slot
     *  - Minimum booking notice buffer for same-day bookings
     */
    @Transactional(readOnly = true)
    public List<AvailableSlotDto> getAvailableTimeSlots(LocalDate date, UUID serviceId, UUID subServiceId) {
        int dayOfWeek = date.getDayOfWeek().getValue();

        // 1. Check Date Override (completely closed)
        CalendarDateOverride override = getEffectiveDateOverride(serviceId, date);
        if (override != null && Boolean.TRUE.equals(override.getIsClosed())) {
            return Collections.emptyList();
        }

        // 2. Check Business Working Hours (business closed on this weekday)
        BusinessWorkingHours businessHours = getEffectiveWorkingHours(serviceId, dayOfWeek);
        if (businessHours != null && !Boolean.TRUE.equals(businessHours.getIsOpen())) {
            return Collections.emptyList();
        }

        // 3. Find eligible active workers
        List<Worker> eligibleWorkers = findEligibleWorkers(serviceId, subServiceId);
        if (eligibleWorkers.isEmpty()) {
            return Collections.emptyList();
        }

        // 4. Booking Settings
        BookingSettings settings = getEffectiveSettings(serviceId);
        int slotDurationMins = settings.getSlotDurationMins() != null ? settings.getSlotDurationMins() : 60;
        int maxCapacity = settings.getMaxBookingCapacityPerSlot() != null ? settings.getMaxBookingCapacityPerSlot() : 10;
        int minNoticeMins = settings.getMinBookingNoticeMins() != null ? settings.getMinBookingNoticeMins() : 30;

        // 5. Operating Window Boundaries
        LocalTime minStart = (override != null && override.getCustomOpenTime() != null)
                ? override.getCustomOpenTime()
                : (businessHours != null ? businessHours.getOpenTime() : LocalTime.of(8, 0));
        LocalTime maxEnd = (override != null && override.getCustomCloseTime() != null)
                ? override.getCustomCloseTime()
                : (businessHours != null ? businessHours.getCloseTime() : LocalTime.of(20, 0));

        // 6. Collect worker availability rules for this day of week
        Map<UUID, WorkerAvailability> workerAvailMap = new HashMap<>();
        for (Worker w : eligibleWorkers) {
            Optional<WorkerAvailability> waOpt = availabilityRepository.findByWorker_WorkerIdAndDayOfWeek(w.getWorkerId(), dayOfWeek);
            if (waOpt.isPresent() && Boolean.TRUE.equals(waOpt.get().getIsAvailable())) {
                workerAvailMap.put(w.getWorkerId(), waOpt.get());
            } else if (waOpt.isEmpty() && dayOfWeek <= 6) {
                // Default schedule if unconfigured
                workerAvailMap.put(w.getWorkerId(), WorkerAvailability.builder()
                        .worker(w)
                        .dayOfWeek(dayOfWeek)
                        .isAvailable(true)
                        .startTime(LocalTime.of(8, 0))
                        .endTime(LocalTime.of(20, 0))
                        .breakStartTime(LocalTime.of(13, 0))
                        .breakEndTime(LocalTime.of(14, 0))
                        .slotDurationMins(60)
                        .build());
            }
        }

        // 7. Filter out workers who have time off on this date
        Set<UUID> workersOnLeave = new HashSet<>();
        for (UUID workerId : workerAvailMap.keySet()) {
            List<WorkerTimeOff> leaves = timeOffRepository.findByWorkerAndDate(workerId, date);
            if (!leaves.isEmpty()) {
                workersOnLeave.add(workerId);
            }
        }

        Set<UUID> activeWorkerIds = new HashSet<>(workerAvailMap.keySet());
        activeWorkerIds.removeAll(workersOnLeave);

        if (activeWorkerIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 8. Current IST timestamp buffer check for same-day bookings
        ZonedDateTime nowIst = ZonedDateTime.now(IST_ZONE);
        boolean isToday = date.equals(nowIst.toLocalDate());
        LocalTime minimumBookingTime = nowIst.toLocalTime().plusMinutes(minNoticeMins);

        List<AvailableSlotDto> slots = new ArrayList<>();
        LocalTime current = minStart;

        while (current.plusMinutes(slotDurationMins).isBefore(maxEnd) || current.plusMinutes(slotDurationMins).equals(maxEnd)) {
            LocalTime slotStart = current;
            LocalTime slotEnd = current.plusMinutes(slotDurationMins);
            current = slotEnd;

            boolean isBusinessBreak = false;
            if (businessHours != null && businessHours.getBreakStartTime() != null && businessHours.getBreakEndTime() != null) {
                if (slotStart.isBefore(businessHours.getBreakEndTime()) && slotEnd.isAfter(businessHours.getBreakStartTime())) {
                    isBusinessBreak = true;
                }
            }

            boolean isBlockedWindow = false;
            if (override != null && override.getBlockedStartTime() != null && override.getBlockedEndTime() != null) {
                if (slotStart.isBefore(override.getBlockedEndTime()) && slotEnd.isAfter(override.getBlockedStartTime())) {
                    isBlockedWindow = true;
                }
            }

            // Compute Instant window for this slot in IST
            Instant startInstant = date.atTime(slotStart).atZone(IST_ZONE).toInstant();
            Instant endInstant = date.atTime(slotEnd).atZone(IST_ZONE).toInstant();

            // Count eligible available workers for this specific slot
            int availableCount = 0;
            if (!isBusinessBreak && !isBlockedWindow) {
                for (UUID workerId : activeWorkerIds) {
                    WorkerAvailability wa = workerAvailMap.get(workerId);
                    // Check if slot falls in worker's shift
                    if (slotStart.isBefore(wa.getStartTime()) || slotEnd.isAfter(wa.getEndTime())) {
                        continue;
                    }
                    // Check if slot overlaps with worker's break
                    if (wa.getBreakStartTime() != null && wa.getBreakEndTime() != null) {
                        if (slotStart.isBefore(wa.getBreakEndTime()) && slotEnd.isAfter(wa.getBreakStartTime())) {
                            continue;
                        }
                    }
                    // Check if worker has an active overlapping booking in this slot
                    long overlaps = bookingRepository.countOverlappingBookings(workerId, startInstant, endInstant, ACTIVE_STAGES);
                    if (overlaps == 0) {
                        availableCount++;
                    }
                }
            }

            // Slot is available if at least 1 worker is free AND (if today) slot hasn't passed
            boolean isTimeValid = !isToday || slotStart.isAfter(minimumBookingTime);
            boolean isSlotAvailable = isTimeValid && !isBusinessBreak && !isBlockedWindow && availableCount > 0;

            String label = slotStart.format(TIME_FORMATTER) + " - " + slotEnd.format(TIME_FORMATTER);
            String period = slotStart.getHour() < 12 ? "MORNING" : (slotStart.getHour() < 16 ? "AFTERNOON" : "EVENING");

            String reason = null;
            if (!isTimeValid) {
                reason = "Time slot has passed";
            } else if (isBusinessBreak) {
                reason = "Lunch / Break time";
            } else if (isBlockedWindow) {
                reason = "Blocked window: " + (override != null ? override.getReason() : "");
            } else if (availableCount == 0) {
                reason = "All partners booked";
            }

            slots.add(AvailableSlotDto.builder()
                    .time(label)
                    .startTime(slotStart.format(TIME_24_FORMATTER))
                    .endTime(slotEnd.format(TIME_24_FORMATTER))
                    .available(isSlotAvailable)
                    .period(period)
                    .availableWorkersCount(availableCount)
                    .reason(reason)
                    .build());
        }

        return slots;
    }

    /**
     * Checks if a worker has an overlapping booking or leave for [start, end].
     */
    @Transactional(readOnly = true)
    public boolean isWorkerAvailableForWindow(UUID workerId, Instant start, Instant end) {
        LocalDate date = start.atZone(IST_ZONE).toLocalDate();
        LocalTime startTime = start.atZone(IST_ZONE).toLocalTime();
        LocalTime endTime = end.atZone(IST_ZONE).toLocalTime();

        // 1. Time off
        if (!timeOffRepository.findByWorkerAndDate(workerId, date).isEmpty()) {
            return false;
        }

        // 2. Weekly schedule
        int dayOfWeek = date.getDayOfWeek().getValue();
        Optional<WorkerAvailability> waOpt = availabilityRepository.findByWorker_WorkerIdAndDayOfWeek(workerId, dayOfWeek);
        if (waOpt.isPresent()) {
            WorkerAvailability wa = waOpt.get();
            if (!Boolean.TRUE.equals(wa.getIsAvailable())) return false;
            if (startTime.isBefore(wa.getStartTime()) || endTime.isAfter(wa.getEndTime())) return false;
            if (wa.getBreakStartTime() != null && wa.getBreakEndTime() != null) {
                if (startTime.isBefore(wa.getBreakEndTime()) && endTime.isAfter(wa.getBreakStartTime())) {
                    return false;
                }
            }
        }

        // 3. Existing overlapping bookings in DB
        long overlaps = bookingRepository.countOverlappingBookings(workerId, start, end, ACTIVE_STAGES);
        return overlaps == 0;
    }

    /**
     * Today's schedule for worker.
     */
    @Transactional(readOnly = true)
    public WorkerTodayScheduleResponse getTodaySchedule(UUID workerId) {
        LocalDate today = LocalDate.now(IST_ZONE);
        Instant dayStart = today.atStartOfDay(IST_ZONE).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(IST_ZONE).toInstant();

        List<Booking> todayBookings = bookingRepository.findByWorker_WorkerIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                workerId, dayStart, dayEnd);

        List<WorkerScheduleSlotDto> slots = new ArrayList<>();
        int completed = 0;

        for (Booking b : todayBookings) {
            if (b.getStatus() == BookingStage.COMPLETED) {
                completed++;
            }
            LocalTime start = b.getScheduledAt().atZone(IST_ZONE).toLocalTime();
            LocalTime end = b.getScheduledEndAt() != null
                    ? b.getScheduledEndAt().atZone(IST_ZONE).toLocalTime()
                    : start.plusHours(1);

            String timeWindow = start.format(TIME_FORMATTER) + " - " + end.format(TIME_FORMATTER);

            slots.add(WorkerScheduleSlotDto.builder()
                    .bookingId(b.getBookingId())
                    .serviceName(b.getService() != null ? b.getService().getName() : "Service")
                    .customerName(b.getUser() != null ? b.getUser().getFullName() : "Customer")
                    .customerPhone(b.getUser() != null ? b.getUser().getPhoneNumber() : "")
                    .address(b.getRecipientAddress() != null ? b.getRecipientAddress() : "Customer Doorstep")
                    .timeWindow(timeWindow)
                    .status(b.getStatus().name())
                    .scheduledAt(b.getScheduledAt())
                    .scheduledEndAt(b.getScheduledEndAt())
                    .bookingType(b.getBookingType() != null ? b.getBookingType().name() : "SCHEDULED")
                    .build());
        }

        return WorkerTodayScheduleResponse.builder()
                .date(today.toString())
                .totalJobsToday(todayBookings.size())
                .completedCount(completed)
                .schedule(slots)
                .build();
    }

    /**
     * Upcoming scheduled bookings for worker (from tomorrow onwards).
     */
    @Transactional(readOnly = true)
    public List<WorkerScheduleSlotDto> getUpcomingSchedule(UUID workerId) {
        LocalDate tomorrow = LocalDate.now(IST_ZONE).plusDays(1);
        Instant startInstant = tomorrow.atStartOfDay(IST_ZONE).toInstant();
        Instant endInstant = tomorrow.plusDays(30).atStartOfDay(IST_ZONE).toInstant();

        List<Booking> upcoming = bookingRepository.findByWorker_WorkerIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                workerId, startInstant, endInstant);

        return upcoming.stream().map(b -> {
            LocalTime start = b.getScheduledAt().atZone(IST_ZONE).toLocalTime();
            LocalTime end = b.getScheduledEndAt() != null
                    ? b.getScheduledEndAt().atZone(IST_ZONE).toLocalTime()
                    : start.plusHours(1);
            String timeWindow = b.getScheduledAt().atZone(IST_ZONE).toLocalDate() + " " +
                    start.format(TIME_FORMATTER) + " - " + end.format(TIME_FORMATTER);

            return WorkerScheduleSlotDto.builder()
                    .bookingId(b.getBookingId())
                    .serviceName(b.getService() != null ? b.getService().getName() : "Service")
                    .customerName(b.getUser() != null ? b.getUser().getFullName() : "Customer")
                    .customerPhone(b.getUser() != null ? b.getUser().getPhoneNumber() : "")
                    .address(b.getRecipientAddress() != null ? b.getRecipientAddress() : "Customer Doorstep")
                    .timeWindow(timeWindow)
                    .status(b.getStatus().name())
                    .scheduledAt(b.getScheduledAt())
                    .scheduledEndAt(b.getScheduledEndAt())
                    .bookingType(b.getBookingType() != null ? b.getBookingType().name() : "SCHEDULED")
                    .build();
        }).toList();
    }

    private List<Worker> findEligibleWorkers(UUID serviceId, UUID subServiceId) {
        if (subServiceId != null) {
            List<WorkerServiceLink> links = workerServiceLinkRepository.findById_SubServiceId(subServiceId);
            return links.stream()
                    .map(WorkerServiceLink::getWorker)
                    .filter(w -> Boolean.TRUE.equals(w.getIsVerified()))
                    .toList();
        }
        return workerRepository.findAll().stream()
                .filter(w -> Boolean.TRUE.equals(w.getIsVerified()))
                .toList();
    }

    private LocalTime parseTime(String str, LocalTime defaultTime) {
        if (str == null || str.isBlank()) return defaultTime;
        try {
            return LocalTime.parse(str.trim());
        } catch (Exception e) {
            return defaultTime;
        }
    }
}

