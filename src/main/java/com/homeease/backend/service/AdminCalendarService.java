package com.homeease.backend.service;

import com.homeease.backend.dto.AdminCalendarDto.*;
import com.homeease.backend.dto.WorkerDto.DayScheduleDto;
import com.homeease.backend.dto.WorkerDto.TimeOffResponse;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
public class AdminCalendarService {

    private static final Logger logger = LoggerFactory.getLogger(AdminCalendarService.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
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

    private final BookingRepository bookingRepository;
    private final WorkerRepository workerRepository;
    private final ServiceRepository serviceRepository;
    private final WorkerServiceLinkRepository workerServiceLinkRepository;
    private final BusinessWorkingHoursRepository workingHoursRepository;
    private final CalendarDateOverrideRepository overrideRepository;
    private final BookingSettingsRepository bookingSettingsRepository;
    private final BookingSlotRepository slotRepository;
    private final UserRepository userRepository;
    private final WorkerAvailabilityService availabilityService;
    private final BookingNotificationService notificationService;
    private final BookingAuditService auditService;

    public AdminCalendarService(BookingRepository bookingRepository,
                                WorkerRepository workerRepository,
                                ServiceRepository serviceRepository,
                                WorkerServiceLinkRepository workerServiceLinkRepository,
                                BusinessWorkingHoursRepository workingHoursRepository,
                                CalendarDateOverrideRepository overrideRepository,
                                BookingSettingsRepository bookingSettingsRepository,
                                BookingSlotRepository slotRepository,
                                UserRepository userRepository,
                                WorkerAvailabilityService availabilityService,
                                BookingNotificationService notificationService,
                                BookingAuditService auditService) {
        this.bookingRepository = bookingRepository;
        this.workerRepository = workerRepository;
        this.serviceRepository = serviceRepository;
        this.workerServiceLinkRepository = workerServiceLinkRepository;
        this.workingHoursRepository = workingHoursRepository;
        this.overrideRepository = overrideRepository;
        this.bookingSettingsRepository = bookingSettingsRepository;
        this.slotRepository = slotRepository;
        this.userRepository = userRepository;
        this.availabilityService = availabilityService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    // =========================================================================
    // 1. CALENDAR DASHBOARD & EVENTS (Day / Week / Month Views)
    // =========================================================================

    @Transactional(readOnly = true)
    public Page<CalendarEventDto> getCalendarEvents(
            String view,
            LocalDate date,
            LocalDate startDate,
            LocalDate endDate,
            UUID serviceId,
            UUID workerId,
            BookingStage status,
            BookingType bookingType,
            UUID customerId,
            Boolean unassignedOnly,
            String search,
            Pageable pageable) {

        // 1. Resolve date boundaries based on view or custom date range
        LocalDate refDate = date != null ? date : LocalDate.now(IST_ZONE);
        LocalDate start = startDate;
        LocalDate end = endDate;

        if (start == null || end == null) {
            if ("day".equalsIgnoreCase(view)) {
                start = refDate;
                end = refDate;
            } else if ("week".equalsIgnoreCase(view)) {
                start = refDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                end = refDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            } else { // default to month view
                start = refDate.with(TemporalAdjusters.firstDayOfMonth());
                end = refDate.with(TemporalAdjusters.lastDayOfMonth());
            }
        }

        Instant startInstant = start.atStartOfDay(IST_ZONE).toInstant();
        Instant endInstant = end.plusDays(1).atStartOfDay(IST_ZONE).toInstant();

        // 2. Build multi-criteria Specification
        Specification<Booking> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Date range filter
            predicates.add(cb.between(root.get("scheduledAt"), startInstant, endInstant));

            // Service filter
            if (serviceId != null) {
                predicates.add(cb.equal(root.get("service").get("serviceId"), serviceId));
            }

            // Worker filter
            if (workerId != null) {
                predicates.add(cb.equal(root.get("worker").get("workerId"), workerId));
            }

            // Unassigned filter
            if (Boolean.TRUE.equals(unassignedOnly)) {
                predicates.add(cb.isNull(root.get("worker")));
            }

            // Status filter
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            // Booking type filter
            if (bookingType != null) {
                predicates.add(cb.equal(root.get("bookingType"), bookingType));
            }

            // Customer filter
            if (customerId != null) {
                predicates.add(cb.equal(root.get("user").get("userId"), customerId));
            }

            // Text search (search in recipientName, customer fullName, phone, address, pincode)
            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase() + "%";
                Predicate searchPredicate = cb.or(
                        cb.like(cb.lower(root.get("user").get("fullName")), term),
                        cb.like(cb.lower(root.get("user").get("phoneNumber")), term),
                        cb.like(cb.lower(root.get("recipientName")), term),
                        cb.like(cb.lower(root.get("recipientPhone")), term),
                        cb.like(cb.lower(root.get("servicePincode")), term),
                        cb.like(cb.lower(root.get("pinCode")), term)
                );
                predicates.add(searchPredicate);
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Booking> page = bookingRepository.findAll(spec, pageable);
        return page.map(this::mapToEventDto);
    }

    @Transactional(readOnly = true)
    public CalendarSummaryDto getCalendarSummary(LocalDate startDate, LocalDate endDate, UUID serviceId, UUID workerId) {
        LocalDate start = startDate != null ? startDate : LocalDate.now(IST_ZONE).with(TemporalAdjusters.firstDayOfMonth());
        LocalDate end = endDate != null ? endDate : LocalDate.now(IST_ZONE).with(TemporalAdjusters.lastDayOfMonth());

        Instant startInstant = start.atStartOfDay(IST_ZONE).toInstant();
        Instant endInstant = end.plusDays(1).atStartOfDay(IST_ZONE).toInstant();

        Specification<Booking> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.between(root.get("scheduledAt"), startInstant, endInstant));
            if (serviceId != null) predicates.add(cb.equal(root.get("service").get("serviceId"), serviceId));
            if (workerId != null) predicates.add(cb.equal(root.get("worker").get("workerId"), workerId));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        List<Booking> bookings = bookingRepository.findAll(spec);

        long total = bookings.size();
        long instant = bookings.stream().filter(b -> b.getBookingType() == BookingType.INSTANT).count();
        long scheduled = bookings.stream().filter(b -> b.getBookingType() == BookingType.SCHEDULED).count();
        long pending = bookings.stream().filter(b -> b.getStatus() == BookingStage.PENDING || b.getStatus() == BookingStage.REQUESTED || b.getStatus() == BookingStage.SEARCHING).count();
        long confirmed = bookings.stream().filter(b -> b.getStatus() == BookingStage.CONFIRMED || b.getStatus() == BookingStage.ACCEPTED).count();
        long inProgress = bookings.stream().filter(b -> b.getStatus() == BookingStage.IN_PROGRESS || b.getStatus() == BookingStage.ON_THE_WAY || b.getStatus() == BookingStage.ARRIVED).count();
        long completed = bookings.stream().filter(b -> b.getStatus() == BookingStage.COMPLETED).count();
        long cancelled = bookings.stream().filter(b -> b.getStatus() == BookingStage.CANCELLED || b.getStatus() == BookingStage.REJECTED || b.getStatus() == BookingStage.EXPIRED).count();
        long unassigned = bookings.stream().filter(b -> b.getWorker() == null).count();

        return CalendarSummaryDto.builder()
                .startDate(start.toString())
                .endDate(end.toString())
                .totalBookings(total)
                .instantBookings(instant)
                .scheduledBookings(scheduled)
                .pendingBookings(pending)
                .confirmedBookings(confirmed)
                .inProgressBookings(inProgress)
                .completedBookings(completed)
                .cancelledBookings(cancelled)
                .unassignedBookings(unassigned)
                .build();
    }

    @Transactional(readOnly = true)
    public List<CalendarDayAvailabilityDto> getCalendarAvailabilityOverview(LocalDate startDate, LocalDate endDate, UUID serviceId) {
        LocalDate start = startDate != null ? startDate : LocalDate.now(IST_ZONE);
        LocalDate end = endDate != null ? endDate : start.plusDays(13);

        List<CalendarDayAvailabilityDto> result = new ArrayList<>();
        LocalDate cur = start;

        while (!cur.isAfter(end)) {
            int dayOfWeek = cur.getDayOfWeek().getValue();

            CalendarDateOverride override = availabilityService.getEffectiveDateOverride(serviceId, cur);
            BusinessWorkingHours hours = availabilityService.getEffectiveWorkingHours(serviceId, dayOfWeek);

            boolean isOpen = true;
            String overrideTypeStr = null;
            String reason = null;
            boolean isSpecial = false;

            if (override != null) {
                isSpecial = true;
                overrideTypeStr = override.getOverrideType().name();
                reason = override.getReason();
                if (Boolean.TRUE.equals(override.getIsClosed())) {
                    isOpen = false;
                }
            } else if (hours != null && !Boolean.TRUE.equals(hours.getIsOpen())) {
                isOpen = false;
                reason = "Weekly closed day";
            }

            LocalTime openTime = (override != null && override.getCustomOpenTime() != null)
                    ? override.getCustomOpenTime()
                    : (hours != null ? hours.getOpenTime() : LocalTime.of(8, 0));
            LocalTime closeTime = (override != null && override.getCustomCloseTime() != null)
                    ? override.getCustomCloseTime()
                    : (hours != null ? hours.getCloseTime() : LocalTime.of(20, 0));

            // Available slots count
            var slots = availabilityService.getAvailableTimeSlots(cur, serviceId, null);
            long availableSlotsCount = slots.stream().filter(s -> Boolean.TRUE.equals(s.getAvailable())).count();

            // Total bookings on this day
            Instant dayStart = cur.atStartOfDay(IST_ZONE).toInstant();
            Instant dayEnd = cur.plusDays(1).atStartOfDay(IST_ZONE).toInstant();
            Specification<Booking> spec = (root, q, cb) -> cb.between(root.get("scheduledAt"), dayStart, dayEnd);
            long totalBookingsCount = bookingRepository.count(spec);

            long activeWorkersCount = workerRepository.findAll().stream()
                    .filter(w -> Boolean.TRUE.equals(w.getIsOnline()))
                    .count();

            result.add(CalendarDayAvailabilityDto.builder()
                    .date(cur.toString())
                    .dayOfWeek(dayOfWeek)
                    .dayName(cur.getDayOfWeek().name())
                    .isOpen(isOpen)
                    .openTime(openTime != null ? openTime.format(TIME_24_FORMATTER) : null)
                    .closeTime(closeTime != null ? closeTime.format(TIME_24_FORMATTER) : null)
                    .isSpecialOverride(isSpecial)
                    .overrideType(overrideTypeStr)
                    .overrideReason(reason)
                    .totalActiveWorkers((int) activeWorkersCount)
                    .availableSlotsCount((int) availableSlotsCount)
                    .totalBookingsCount((int) totalBookingsCount)
                    .build());

            cur = cur.plusDays(1);
        }

        return result;
    }

    // =========================================================================
    // 2. WORKING HOURS MANAGEMENT
    // =========================================================================

    @Transactional(readOnly = true)
    public List<WorkingHoursDto> getWorkingHours(UUID serviceId) {
        List<BusinessWorkingHours> hoursList = (serviceId != null)
                ? workingHoursRepository.findByService_ServiceIdOrderByDayOfWeekAsc(serviceId)
                : workingHoursRepository.findByServiceIsNullOrderByDayOfWeekAsc();

        Map<Integer, BusinessWorkingHours> map = new HashMap<>();
        for (BusinessWorkingHours b : hoursList) {
            map.put(b.getDayOfWeek(), b);
        }

        ServiceEntity service = serviceId != null ? serviceRepository.findById(serviceId).orElse(null) : null;
        String serviceName = service != null ? service.getName() : "Global Default";

        List<WorkingHoursDto> result = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            BusinessWorkingHours b = map.get(day);
            if (b != null) {
                result.add(WorkingHoursDto.builder()
                        .id(b.getId())
                        .serviceId(serviceId)
                        .serviceName(serviceName)
                        .dayOfWeek(day)
                        .dayName(DAY_NAMES[day])
                        .isOpen(b.getIsOpen())
                        .openTime(b.getOpenTime() != null ? b.getOpenTime().format(TIME_24_FORMATTER) : "08:00")
                        .closeTime(b.getCloseTime() != null ? b.getCloseTime().format(TIME_24_FORMATTER) : "20:00")
                        .breakStartTime(b.getBreakStartTime() != null ? b.getBreakStartTime().format(TIME_24_FORMATTER) : "13:00")
                        .breakEndTime(b.getBreakEndTime() != null ? b.getBreakEndTime().format(TIME_24_FORMATTER) : "14:00")
                        .build());
            } else {
                result.add(WorkingHoursDto.builder()
                        .serviceId(serviceId)
                        .serviceName(serviceName)
                        .dayOfWeek(day)
                        .dayName(DAY_NAMES[day])
                        .isOpen(day <= 6)
                        .openTime("08:00")
                        .closeTime("20:00")
                        .breakStartTime("13:00")
                        .breakEndTime("14:00")
                        .build());
            }
        }

        return result;
    }

    @Transactional
    public List<WorkingHoursDto> saveWorkingHours(SaveWorkingHoursRequest request, UUID adminId) {
        UUID serviceId = request.getServiceId();
        ServiceEntity service = serviceId != null
                ? serviceRepository.findById(serviceId).orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId))
                : null;

        List<WorkingHoursDto> previous = getWorkingHours(serviceId);

        for (WorkingHoursDto dto : request.getSchedules()) {
            if (dto.getDayOfWeek() == null || dto.getDayOfWeek() < 1 || dto.getDayOfWeek() > 7) continue;

            LocalTime open = parseTime(dto.getOpenTime(), LocalTime.of(8, 0));
            LocalTime close = parseTime(dto.getCloseTime(), LocalTime.of(20, 0));
            LocalTime breakStart = parseTime(dto.getBreakStartTime(), LocalTime.of(13, 0));
            LocalTime breakEnd = parseTime(dto.getBreakEndTime(), LocalTime.of(14, 0));

            Optional<BusinessWorkingHours> existingOpt = (serviceId != null)
                    ? workingHoursRepository.findByService_ServiceIdAndDayOfWeek(serviceId, dto.getDayOfWeek())
                    : workingHoursRepository.findByServiceIsNullAndDayOfWeek(dto.getDayOfWeek());

            BusinessWorkingHours entity = existingOpt.orElseGet(() -> BusinessWorkingHours.builder()
                    .service(service)
                    .dayOfWeek(dto.getDayOfWeek())
                    .build());

            entity.setIsOpen(Boolean.TRUE.equals(dto.getIsOpen()));
            entity.setOpenTime(open);
            entity.setCloseTime(close);
            entity.setBreakStartTime(breakStart);
            entity.setBreakEndTime(breakEnd);

            workingHoursRepository.save(entity);
        }

        List<WorkingHoursDto> updated = getWorkingHours(serviceId);

        // Audit log & Cache invalidation
        auditService.recordLog(null, "WORKING_HOURS", serviceId != null ? serviceId.toString() : "GLOBAL",
                adminId, "UPDATE_WORKING_HOURS", previous, updated, "Updated business operating hours");
        availabilityService.invalidateCalendarCache();

        return updated;
    }

    @Transactional
    public List<WorkingHoursDto> copyWorkingHours(CopyWorkingHoursRequest request, UUID adminId) {
        UUID serviceId = request.getServiceId();
        int srcDay = request.getSourceDayOfWeek();

        Optional<BusinessWorkingHours> srcOpt = (serviceId != null)
                ? workingHoursRepository.findByService_ServiceIdAndDayOfWeek(serviceId, srcDay)
                : workingHoursRepository.findByServiceIsNullAndDayOfWeek(srcDay);

        BusinessWorkingHours src = srcOpt.orElseGet(() -> BusinessWorkingHours.builder()
                .dayOfWeek(srcDay)
                .isOpen(srcDay <= 6)
                .openTime(LocalTime.of(8, 0))
                .closeTime(LocalTime.of(20, 0))
                .breakStartTime(LocalTime.of(13, 0))
                .breakEndTime(LocalTime.of(14, 0))
                .build());

        ServiceEntity service = serviceId != null ? serviceRepository.findById(serviceId).orElse(null) : null;

        for (Integer targetDay : request.getTargetDaysOfWeek()) {
            if (targetDay == null || targetDay < 1 || targetDay > 7 || targetDay == srcDay) continue;

            Optional<BusinessWorkingHours> targetOpt = (serviceId != null)
                    ? workingHoursRepository.findByService_ServiceIdAndDayOfWeek(serviceId, targetDay)
                    : workingHoursRepository.findByServiceIsNullAndDayOfWeek(targetDay);

            BusinessWorkingHours target = targetOpt.orElseGet(() -> BusinessWorkingHours.builder()
                    .service(service)
                    .dayOfWeek(targetDay)
                    .build());

            target.setIsOpen(src.getIsOpen());
            target.setOpenTime(src.getOpenTime());
            target.setCloseTime(src.getCloseTime());
            target.setBreakStartTime(src.getBreakStartTime());
            target.setBreakEndTime(src.getBreakEndTime());

            workingHoursRepository.save(target);
        }

        auditService.recordLog(null, "WORKING_HOURS", serviceId != null ? serviceId.toString() : "GLOBAL",
                adminId, "COPY_WORKING_HOURS", "Copied from day " + srcDay, "Applied to days: " + request.getTargetDaysOfWeek(), "Weekday schedule copy");
        availabilityService.invalidateCalendarCache();

        return getWorkingHours(serviceId);
    }

    // =========================================================================
    // 3. DATE OVERRIDES (Special Dates, Blocked Dates, Public/Festival Holidays)
    // =========================================================================

    @Transactional(readOnly = true)
    public List<DateOverrideDto> getDateOverrides(LocalDate startDate, LocalDate endDate, UUID serviceId) {
        LocalDate start = startDate != null ? startDate : LocalDate.now(IST_ZONE).with(TemporalAdjusters.firstDayOfMonth());
        LocalDate end = endDate != null ? endDate : LocalDate.now(IST_ZONE).plusMonths(2);

        List<CalendarDateOverride> list = (serviceId != null)
                ? overrideRepository.findByService_ServiceIdAndOverrideDateBetweenOrderByOverrideDateAsc(serviceId, start, end)
                : overrideRepository.findByOverrideDateBetweenOrderByOverrideDateAsc(start, end);

        return list.stream().map(this::mapToOverrideDto).toList();
    }

    @Transactional
    public DateOverrideDto createDateOverride(CreateDateOverrideRequest request, UUID adminId) {
        ServiceEntity service = request.getServiceId() != null
                ? serviceRepository.findById(request.getServiceId()).orElseThrow(() -> new ResourceNotFoundException("Service not found"))
                : null;
        User admin = adminId != null ? userRepository.findById(adminId).orElse(null) : null;

        LocalTime customOpen = parseTime(request.getCustomOpenTime(), null);
        LocalTime customClose = parseTime(request.getCustomCloseTime(), null);
        LocalTime blockedStart = parseTime(request.getBlockedStartTime(), null);
        LocalTime blockedEnd = parseTime(request.getBlockedEndTime(), null);

        CalendarDateOverride override = CalendarDateOverride.builder()
                .service(service)
                .overrideDate(request.getOverrideDate())
                .overrideType(request.getOverrideType())
                .isClosed(Boolean.TRUE.equals(request.getIsClosed()))
                .customOpenTime(customOpen)
                .customCloseTime(customClose)
                .blockedStartTime(blockedStart)
                .blockedEndTime(blockedEnd)
                .reason(request.getReason() != null ? request.getReason().trim() : request.getOverrideType().name())
                .createdBy(admin)
                .build();

        override = overrideRepository.save(override);

        auditService.recordLog(null, "CALENDAR_OVERRIDE", override.getId().toString(), adminId,
                "CREATE_DATE_OVERRIDE", null, override, "Added date override for " + override.getOverrideDate());
        availabilityService.invalidateCalendarCache();

        return mapToOverrideDto(override);
    }

    @Transactional
    public DateOverrideDto updateDateOverride(UUID id, UpdateDateOverrideRequest request, UUID adminId) {
        CalendarDateOverride override = overrideRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Date override not found: " + id));

        String prev = override.toString();

        if (request.getOverrideType() != null) override.setOverrideType(request.getOverrideType());
        if (request.getIsClosed() != null) override.setIsClosed(request.getIsClosed());
        if (request.getCustomOpenTime() != null) override.setCustomOpenTime(parseTime(request.getCustomOpenTime(), null));
        if (request.getCustomCloseTime() != null) override.setCustomCloseTime(parseTime(request.getCustomCloseTime(), null));
        if (request.getBlockedStartTime() != null) override.setBlockedStartTime(parseTime(request.getBlockedStartTime(), null));
        if (request.getBlockedEndTime() != null) override.setBlockedEndTime(parseTime(request.getBlockedEndTime(), null));
        if (request.getReason() != null) override.setReason(request.getReason().trim());

        override = overrideRepository.save(override);

        auditService.recordLog(null, "CALENDAR_OVERRIDE", override.getId().toString(), adminId,
                "UPDATE_DATE_OVERRIDE", prev, override, "Updated date override for " + override.getOverrideDate());
        availabilityService.invalidateCalendarCache();

        return mapToOverrideDto(override);
    }

    @Transactional
    public void deleteDateOverride(UUID id, UUID adminId) {
        CalendarDateOverride override = overrideRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Date override not found: " + id));

        overrideRepository.delete(override);

        auditService.recordLog(null, "CALENDAR_OVERRIDE", id.toString(), adminId,
                "DELETE_DATE_OVERRIDE", override.getReason(), "DELETED / REOPENED", "Removed date override for " + override.getOverrideDate());
        availabilityService.invalidateCalendarCache();
    }

    // =========================================================================
    // 4. BOOKING SETTINGS (Slot Duration, Buffer, Advance Limit, Capacity)
    // =========================================================================

    @Transactional(readOnly = true)
    public BookingSettingsDto getBookingSettings(UUID serviceId) {
        BookingSettings s = availabilityService.getEffectiveSettings(serviceId);
        String serviceName = (s.getService() != null) ? s.getService().getName() : "Global Default";

        return BookingSettingsDto.builder()
                .settingId(s.getSettingId())
                .serviceId(s.getService() != null ? s.getService().getServiceId() : null)
                .serviceName(serviceName)
                .slotDurationMins(s.getSlotDurationMins())
                .bufferDurationMins(s.getBufferDurationMins())
                .advanceBookingDays(s.getAdvanceBookingDays())
                .minBookingNoticeMins(s.getMinBookingNoticeMins())
                .maxBookingCapacityPerSlot(s.getMaxBookingCapacityPerSlot())
                .isActive(s.getIsActive())
                .build();
    }

    @Transactional
    public BookingSettingsDto updateBookingSettings(UpdateBookingSettingsRequest request, UUID adminId) {
        UUID serviceId = request.getServiceId();
        ServiceEntity service = serviceId != null
                ? serviceRepository.findById(serviceId).orElseThrow(() -> new ResourceNotFoundException("Service not found"))
                : null;

        Optional<BookingSettings> existingOpt = (serviceId != null)
                ? bookingSettingsRepository.findByService_ServiceId(serviceId)
                : bookingSettingsRepository.findByServiceIsNull();

        BookingSettings setting = existingOpt.orElseGet(() -> BookingSettings.builder()
                .service(service)
                .build());

        BookingSettingsDto prev = getBookingSettings(serviceId);

        if (request.getSlotDurationMins() != null) setting.setSlotDurationMins(request.getSlotDurationMins());
        if (request.getBufferDurationMins() != null) setting.setBufferDurationMins(request.getBufferDurationMins());
        if (request.getAdvanceBookingDays() != null) setting.setAdvanceBookingDays(request.getAdvanceBookingDays());
        if (request.getMinBookingNoticeMins() != null) setting.setMinBookingNoticeMins(request.getMinBookingNoticeMins());
        if (request.getMaxBookingCapacityPerSlot() != null) setting.setMaxBookingCapacityPerSlot(request.getMaxBookingCapacityPerSlot());
        if (request.getIsActive() != null) setting.setIsActive(request.getIsActive());

        setting = bookingSettingsRepository.save(setting);

        BookingSettingsDto updated = getBookingSettings(serviceId);

        auditService.recordLog(null, "SETTINGS", setting.getSettingId().toString(), adminId,
                "UPDATE_SETTINGS", prev, updated, "Updated booking duration, notice, and capacity limits");
        availabilityService.invalidateCalendarCache();

        return updated;
    }

    // =========================================================================
    // 5. TIME SLOT CAPACITY INSPECTION FOR ADMIN
    // =========================================================================

    @Transactional(readOnly = true)
    public DateSlotsCapacityDto getDateSlotsCapacity(LocalDate date, UUID serviceId) {
        LocalDate targetDate = date != null ? date : LocalDate.now(IST_ZONE);
        int dayOfWeek = targetDate.getDayOfWeek().getValue();

        CalendarDateOverride override = availabilityService.getEffectiveDateOverride(serviceId, targetDate);
        BusinessWorkingHours businessHours = availabilityService.getEffectiveWorkingHours(serviceId, dayOfWeek);
        BookingSettings settings = availabilityService.getEffectiveSettings(serviceId);

        boolean isOpen = true;
        String closeReason = null;

        if (override != null && Boolean.TRUE.equals(override.getIsClosed())) {
            isOpen = false;
            closeReason = "Closed: " + override.getReason();
        } else if (businessHours != null && !Boolean.TRUE.equals(businessHours.getIsOpen())) {
            isOpen = false;
            closeReason = "Closed on " + targetDate.getDayOfWeek().name();
        }

        ServiceEntity svc = serviceId != null ? serviceRepository.findById(serviceId).orElse(null) : null;
        String serviceName = svc != null ? svc.getName() : "All Services";

        if (!isOpen) {
            return DateSlotsCapacityDto.builder()
                    .date(targetDate.toString())
                    .serviceId(serviceId)
                    .serviceName(serviceName)
                    .isOpen(false)
                    .closeReason(closeReason)
                    .slots(Collections.emptyList())
                    .build();
        }

        int slotDurationMins = settings.getSlotDurationMins() != null ? settings.getSlotDurationMins() : 60;
        int maxCapacity = settings.getMaxBookingCapacityPerSlot() != null ? settings.getMaxBookingCapacityPerSlot() : 10;

        LocalTime minStart = (override != null && override.getCustomOpenTime() != null)
                ? override.getCustomOpenTime()
                : (businessHours != null ? businessHours.getOpenTime() : LocalTime.of(8, 0));
        LocalTime maxEnd = (override != null && override.getCustomCloseTime() != null)
                ? override.getCustomCloseTime()
                : (businessHours != null ? businessHours.getCloseTime() : LocalTime.of(20, 0));

        List<SlotCapacityDto> slots = new ArrayList<>();
        LocalTime cur = minStart;

        while (cur.plusMinutes(slotDurationMins).isBefore(maxEnd) || cur.plusMinutes(slotDurationMins).equals(maxEnd)) {
            LocalTime slotStart = cur;
            LocalTime slotEnd = cur.plusMinutes(slotDurationMins);
            cur = slotEnd;

            boolean isDisabled = false;
            String disabledReason = null;

            // Business Break
            if (businessHours != null && businessHours.getBreakStartTime() != null && businessHours.getBreakEndTime() != null) {
                if (slotStart.isBefore(businessHours.getBreakEndTime()) && slotEnd.isAfter(businessHours.getBreakStartTime())) {
                    isDisabled = true;
                    disabledReason = "Lunch / Break Window";
                }
            }

            // Blocked Window in Date Override
            if (override != null && override.getBlockedStartTime() != null && override.getBlockedEndTime() != null) {
                if (slotStart.isBefore(override.getBlockedEndTime()) && slotEnd.isAfter(override.getBlockedStartTime())) {
                    isDisabled = true;
                    disabledReason = "Blocked Window: " + override.getReason();
                }
            }

            Instant slotStartInstant = targetDate.atTime(slotStart).atZone(IST_ZONE).toInstant();
            Instant slotEndInstant = targetDate.atTime(slotEnd).atZone(IST_ZONE).toInstant();

            // Find confirmed bookings in this slot
            Specification<Booking> spec = (root, q, cb) -> cb.and(
                    cb.lessThan(root.get("scheduledAt"), slotEndInstant),
                    cb.or(cb.isNull(root.get("scheduledEndAt")), cb.greaterThan(root.get("scheduledEndAt"), slotStartInstant)),
                    root.get("status").in(ACTIVE_STAGES)
            );

            List<Booking> slotBookings = bookingRepository.findAll(spec);
            int bookedCount = slotBookings.size();
            int remaining = Math.max(0, maxCapacity - bookedCount);

            List<String> assignedWorkerNames = slotBookings.stream()
                    .filter(b -> b.getWorker() != null && b.getWorker().getUser() != null)
                    .map(b -> b.getWorker().getUser().getFullName())
                    .toList();
            List<UUID> assignedBookingIds = slotBookings.stream().map(Booking::getBookingId).toList();

            boolean isAvailable = !isDisabled && remaining > 0;
            String period = slotStart.getHour() < 12 ? "MORNING" : (slotStart.getHour() < 16 ? "AFTERNOON" : "EVENING");

            slots.add(SlotCapacityDto.builder()
                    .timeWindow(slotStart.format(TIME_FORMATTER) + " - " + slotEnd.format(TIME_FORMATTER))
                    .startTime(slotStart.format(TIME_24_FORMATTER))
                    .endTime(slotEnd.format(TIME_24_FORMATTER))
                    .period(period)
                    .maxCapacity(maxCapacity)
                    .bookedCount(bookedCount)
                    .remainingCapacity(remaining)
                    .isAvailable(isAvailable)
                    .isDisabled(isDisabled)
                    .disabledReason(disabledReason)
                    .assignedWorkerNames(assignedWorkerNames)
                    .assignedBookingIds(assignedBookingIds)
                    .build());
        }

        return DateSlotsCapacityDto.builder()
                .date(targetDate.toString())
                .serviceId(serviceId)
                .serviceName(serviceName)
                .isOpen(true)
                .slots(slots)
                .build();
    }

    // =========================================================================
    // 6. WORKER CALENDAR MANAGEMENT
    // =========================================================================

    @Transactional(readOnly = true)
    public WorkerCalendarDto getWorkerCalendar(UUID workerId, LocalDate startDate, LocalDate endDate) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new ResourceNotFoundException("Worker not found: " + workerId));

        LocalDate start = startDate != null ? startDate : LocalDate.now(IST_ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = endDate != null ? endDate : start.plusDays(13);

        Instant startInstant = start.atStartOfDay(IST_ZONE).toInstant();
        Instant endInstant = end.plusDays(1).atStartOfDay(IST_ZONE).toInstant();

        // 1. Weekly working schedule
        List<DayScheduleDto> availability = availabilityService.getWorkerAvailability(workerId);

        // 2. Leaves in range
        List<TimeOffResponse> leaves = availabilityService.getWorkerTimeOff(workerId);

        // 3. Assigned Bookings in range
        List<Booking> bookings = bookingRepository.findByWorker_WorkerIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                workerId, startInstant, endInstant);
        List<CalendarEventDto> eventDtos = bookings.stream().map(this::mapToEventDto).toList();

        String workerName = worker.getUser() != null ? worker.getUser().getFullName() : "Partner " + workerId.toString().substring(0, 4);
        String phone = worker.getUser() != null ? worker.getUser().getPhoneNumber() : "";

        return WorkerCalendarDto.builder()
                .workerId(worker.getWorkerId())
                .workerName(workerName)
                .phoneNumber(phone)
                .rating(4.8)
                .isOnline(worker.getIsOnline())
                .isVerified(worker.getIsVerified())
                .weeklyAvailability(availability)
                .leaves(leaves)
                .assignedBookings(eventDtos)
                .build();
    }

    // =========================================================================
    // 7. BOOKING RESCHEDULE & WORKER REASSIGNMENT
    // =========================================================================

    @Transactional
    public CalendarEventDto rescheduleBooking(UUID bookingId, RescheduleBookingRequest request, UUID adminId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        if (booking.getStatus() == BookingStage.COMPLETED || booking.getStatus() == BookingStage.CANCELLED) {
            throw new IllegalStateException("Cannot reschedule a booking in status: " + booking.getStatus());
        }

        Instant oldStart = booking.getScheduledAt();
        Instant oldEnd = booking.getScheduledEndAt();
        Worker oldWorker = booking.getWorker();

        Instant newStart = request.getNewScheduledAt();
        Instant newEnd = request.getNewScheduledEndAt();
        if (newEnd == null) {
            // Default service duration if end time not provided
            int durationMins = 60;
            if (booking.getService() != null) {
                BookingSettings settings = availabilityService.getEffectiveSettings(booking.getService().getServiceId());
                durationMins = settings.getSlotDurationMins() != null ? settings.getSlotDurationMins() : 60;
            }
            newEnd = newStart.plus(Duration.ofMinutes(durationMins));
        }

        // Validate date isn't closed by Date Override or Working Hours
        LocalDate newDate = newStart.atZone(IST_ZONE).toLocalDate();
        int dayOfWeek = newDate.getDayOfWeek().getValue();
        UUID svcId = booking.getService() != null ? booking.getService().getServiceId() : null;

        CalendarDateOverride override = availabilityService.getEffectiveDateOverride(svcId, newDate);
        if (override != null && Boolean.TRUE.equals(override.getIsClosed())) {
            throw new IllegalArgumentException("Cannot reschedule to closed date (" + override.getReason() + ")");
        }

        BusinessWorkingHours hours = availabilityService.getEffectiveWorkingHours(svcId, dayOfWeek);
        if (hours != null && !Boolean.TRUE.equals(hours.getIsOpen())) {
            throw new IllegalArgumentException("Cannot reschedule to weekly closed day: " + newDate.getDayOfWeek().name());
        }

        // Worker validation / assignment
        Worker targetWorker = oldWorker;
        if (request.getWorkerId() != null) {
            targetWorker = workerRepository.findById(request.getWorkerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Target worker not found: " + request.getWorkerId()));
        }

        if (targetWorker != null) {
            // Check overlapping bookings for target worker (excluding this booking)
            long overlaps = bookingRepository.countOverlappingBookings(targetWorker.getWorkerId(), newStart, newEnd, ACTIVE_STAGES);
            // If current booking is already counted in overlaps, 1 overlap is this same booking
            boolean isSelfOverlap = (oldWorker != null && oldWorker.getWorkerId().equals(targetWorker.getWorkerId())
                    && overlaps == 1 && oldStart.isBefore(newEnd) && (oldEnd == null || oldEnd.isAfter(newStart)));
            if (overlaps > 0 && !isSelfOverlap) {
                throw new IllegalStateException("Target worker already has an active conflicting booking in this time slot");
            }
        }

        booking.setScheduledAt(newStart);
        booking.setScheduledEndAt(newEnd);
        if (targetWorker != null && !targetWorker.equals(oldWorker)) {
            booking.setWorker(targetWorker);
            booking.setWorkerAssignedAt(Instant.now());
        }

        booking = bookingRepository.save(booking);

        // Update booking_slots record if exists
        if (booking.getSlotId() != null) {
            Optional<BookingSlot> slotOpt = slotRepository.findById(booking.getSlotId());
            if (slotOpt.isPresent()) {
                BookingSlot slot = slotOpt.get();
                slot.setSlotDate(newDate);
                slot.setStartTime(newStart.atZone(IST_ZONE).toLocalTime());
                slot.setEndTime(newEnd.atZone(IST_ZONE).toLocalTime());
                slotRepository.save(slot);
            }
        }

        // Record Audit Log
        Map<String, Object> prevMap = new LinkedHashMap<>();
        prevMap.put("scheduledAt", oldStart.toString());
        prevMap.put("workerId", oldWorker != null ? oldWorker.getWorkerId().toString() : "UNASSIGNED");

        Map<String, Object> newMap = new LinkedHashMap<>();
        newMap.put("scheduledAt", newStart.toString());
        newMap.put("workerId", targetWorker != null ? targetWorker.getWorkerId().toString() : "UNASSIGNED");

        auditService.recordLog(booking, "BOOKING", booking.getBookingId().toString(), adminId,
                "RESCHEDULE_BOOKING", prevMap, newMap, request.getReason() != null ? request.getReason() : "Admin calendar reschedule");

        // Cache Invalidation & Notification
        availabilityService.invalidateCalendarCache();
        notificationService.notifyBookingRescheduled(booking, oldStart, request.getReason());

        return mapToEventDto(booking);
    }

    @Transactional
    public CalendarEventDto assignWorker(UUID bookingId, AssignWorkerRequest request, UUID adminId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        if (booking.getStatus() == BookingStage.COMPLETED || booking.getStatus() == BookingStage.CANCELLED) {
            throw new IllegalStateException("Cannot reassign worker for booking in status: " + booking.getStatus());
        }

        Worker newWorker = workerRepository.findById(request.getWorkerId())
                .orElseThrow(() -> new ResourceNotFoundException("Worker not found: " + request.getWorkerId()));

        Worker oldWorker = booking.getWorker();

        // Check for double booking conflicts during scheduled time
        Instant start = booking.getScheduledAt();
        Instant end = booking.getScheduledEndAt() != null ? booking.getScheduledEndAt() : start.plus(Duration.ofHours(1));

        long overlaps = bookingRepository.countOverlappingBookings(newWorker.getWorkerId(), start, end, ACTIVE_STAGES);
        if (overlaps > 0) {
            throw new IllegalStateException("Worker already has an active overlapping booking in this time slot");
        }

        booking.setWorker(newWorker);
        booking.setWorkerAssignedAt(Instant.now());

        if (booking.getStatus() == BookingStage.REQUESTED || booking.getStatus() == BookingStage.SEARCHING) {
            booking.setStatus(BookingStage.CONFIRMED);
        }

        booking = bookingRepository.save(booking);

        // Record Audit Log
        auditService.recordLog(booking, "BOOKING", booking.getBookingId().toString(), adminId,
                (oldWorker == null ? "ASSIGN_WORKER" : "REASSIGN_WORKER"),
                oldWorker != null ? oldWorker.getWorkerId().toString() : "UNASSIGNED",
                newWorker.getWorkerId().toString(),
                request.getReason() != null ? request.getReason() : "Admin partner reassignment");

        availabilityService.invalidateCalendarCache();
        notificationService.notifyWorkerReassigned(booking, oldWorker, request.getReason());

        return mapToEventDto(booking);
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private CalendarEventDto mapToEventDto(Booking b) {
        String customerName = b.getUser() != null ? b.getUser().getFullName() : "Customer";
        if (Boolean.TRUE.equals(b.getIsForFriend()) && b.getRecipientName() != null) {
            customerName = b.getRecipientName() + " (Friend)";
        }
        String customerPhone = b.getUser() != null ? b.getUser().getPhoneNumber() : "";
        if (Boolean.TRUE.equals(b.getIsForFriend()) && b.getRecipientPhone() != null) {
            customerPhone = b.getRecipientPhone();
        }

        String workerName = b.getWorker() != null && b.getWorker().getUser() != null
                ? b.getWorker().getUser().getFullName() : null;
        String workerPhone = b.getWorker() != null && b.getWorker().getUser() != null
                ? b.getWorker().getUser().getPhoneNumber() : null;

        String serviceName = b.getService() != null ? b.getService().getName() : "Home Service";

        String timeWindow = "";
        if (b.getScheduledAt() != null) {
            LocalTime st = b.getScheduledAt().atZone(IST_ZONE).toLocalTime();
            LocalTime et = b.getScheduledEndAt() != null
                    ? b.getScheduledEndAt().atZone(IST_ZONE).toLocalTime()
                    : st.plusHours(1);
            timeWindow = st.format(TIME_FORMATTER) + " - " + et.format(TIME_FORMATTER);
        }

        String address = b.getRecipientAddress() != null ? b.getRecipientAddress() : "";

        return CalendarEventDto.builder()
                .bookingId(b.getBookingId())
                .jobId("#HE-" + b.getBookingId().toString().substring(0, 4).toUpperCase())
                .customerId(b.getUser() != null ? b.getUser().getUserId() : null)
                .customerName(customerName)
                .customerPhone(customerPhone)
                .workerId(b.getWorker() != null ? b.getWorker().getWorkerId() : null)
                .workerName(workerName)
                .workerPhone(workerPhone)
                .serviceId(b.getService() != null ? b.getService().getServiceId() : null)
                .serviceName(serviceName)
                .bookingType(b.getBookingType())
                .status(b.getStatus())
                .startAt(b.getScheduledAt())
                .endAt(b.getScheduledEndAt())
                .timeWindow(timeWindow)
                .totalAmount(b.getTotalAmount())
                .address(address)
                .pinCode(b.getPinCode())
                .estimatedArrivalMinutes(b.getEstimatedArrivalMinutes())
                .isUnassigned(b.getWorker() == null)
                .createdAt(b.getCreatedAt())
                .build();
    }

    private DateOverrideDto mapToOverrideDto(CalendarDateOverride o) {
        String serviceName = o.getService() != null ? o.getService().getName() : "Global Override";
        String adminName = o.getCreatedBy() != null ? o.getCreatedBy().getFullName() : "Admin";

        return DateOverrideDto.builder()
                .id(o.getId())
                .serviceId(o.getService() != null ? o.getService().getServiceId() : null)
                .serviceName(serviceName)
                .overrideDate(o.getOverrideDate())
                .overrideType(o.getOverrideType())
                .isClosed(o.getIsClosed())
                .customOpenTime(o.getCustomOpenTime() != null ? o.getCustomOpenTime().format(TIME_24_FORMATTER) : null)
                .customCloseTime(o.getCustomCloseTime() != null ? o.getCustomCloseTime().format(TIME_24_FORMATTER) : null)
                .blockedStartTime(o.getBlockedStartTime() != null ? o.getBlockedStartTime().format(TIME_24_FORMATTER) : null)
                .blockedEndTime(o.getBlockedEndTime() != null ? o.getBlockedEndTime().format(TIME_24_FORMATTER) : null)
                .reason(o.getReason())
                .createdByAdminName(adminName)
                .createdAt(o.getCreatedAt())
                .build();
    }

    private LocalTime parseTime(String str, LocalTime defaultTime) {
        if (str == null || str.isBlank()) return defaultTime;
        try {
            str = str.trim();
            if (str.length() == 5) return LocalTime.parse(str, TIME_24_FORMATTER);
            return LocalTime.parse(str, DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH));
        } catch (Exception e) {
            return defaultTime;
        }
    }
}
