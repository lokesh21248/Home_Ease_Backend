package com.homeease.backend.service;

import com.homeease.backend.dto.ServiceAreaDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.exception.ServiceabilityException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ServiceAreaService {

    private final StateRepository                stateRepository;
    private final CityRepository                 cityRepository;
    private final ServiceablePincodeRepository   pincodeRepository;
    private final UserRepository                 userRepository;
    private final BookingRepository              bookingRepository;

    public ServiceAreaService(StateRepository stateRepository,
                              CityRepository cityRepository,
                              ServiceablePincodeRepository pincodeRepository,
                              UserRepository userRepository,
                              BookingRepository bookingRepository) {
        this.stateRepository   = stateRepository;
        this.cityRepository    = cityRepository;
        this.pincodeRepository = pincodeRepository;
        this.userRepository    = userRepository;
        this.bookingRepository = bookingRepository;
    }

    // ════════════════════════════════════════════════════════════════
    // ADMIN AUTHORIZATION HELPER
    // ════════════════════════════════════════════════════════════════

    /**
     * Verifies the caller has UserRole.ADMIN. Throws SecurityException (→ 403)
     * if not. Follows the same pattern used throughout AdminService.
     */
    private void requireAdmin(UUID callerId) {
        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + callerId));
        if (caller.getRole() != UserRole.ADMIN) {
            throw new SecurityException("Only administrators may manage service areas.");
        }
    }

    // ════════════════════════════════════════════════════════════════
    // OVERVIEW / STATS
    // ════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public ServiceAreaOverviewResponse getOverview() {
        ServiceAreaStats stats = ServiceAreaStats.builder()
                .totalStates(stateRepository.count())
                .totalCities(cityRepository.count())
                .totalPincodes(pincodeRepository.count())
                .activePincodes(pincodeRepository.countByIsActiveTrue())
                .inactivePincodes(pincodeRepository.countByIsActiveFalse())
                .build();

        List<StateResponse> states = stateRepository.findAllByOrderByNameAsc()
                .stream().map(this::mapState).collect(Collectors.toList());

        return ServiceAreaOverviewResponse.builder()
                .stats(stats)
                .states(states)
                .build();
    }

    // ════════════════════════════════════════════════════════════════
    // STATE MANAGEMENT
    // ════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<StateResponse> getAllStates() {
        return stateRepository.findAllByOrderByNameAsc()
                .stream().map(this::mapState).collect(Collectors.toList());
    }

    @Transactional
    public StateResponse createState(UUID callerId, CreateStateRequest req) {
        requireAdmin(callerId);

        String norm = normalize(req.getName());
        if (stateRepository.findByNameNorm(norm).isPresent()) {
            throw new IllegalArgumentException("State '" + req.getName() + "' already exists.");
        }

        State state = State.builder()
                .name(req.getName().trim())
                .nameNorm(norm)
                .isActive(req.getIsActive() != null && req.getIsActive())
                .build();

        return mapState(stateRepository.save(state));
    }

    @Transactional
    public Object updateState(UUID callerId, UUID stateId, UpdateStateRequest req, boolean confirm) {
        requireAdmin(callerId);

        State state = stateRepository.findById(stateId)
                .orElseThrow(() -> new ResourceNotFoundException("State not found: " + stateId));

        // Name change — check for duplicate
        if (req.getName() != null && !req.getName().isBlank()) {
            String norm = normalize(req.getName());
            stateRepository.findByNameNorm(norm).ifPresent(existing -> {
                if (!existing.getStateId().equals(stateId)) {
                    throw new IllegalArgumentException("State '" + req.getName() + "' already exists.");
                }
            });
            state.setName(req.getName().trim());
            state.setNameNorm(norm);
        }

        // Deactivation warning
        if (req.getIsActive() != null && !req.getIsActive() && Boolean.TRUE.equals(state.getIsActive())) {
            long affected = countUpcomingBookingsForState(stateId);
            if (affected > 0 && !confirm) {
                return DeactivationWarningResponse.builder()
                        .warning(true)
                        .affectedUpcomingBookings(affected)
                        .message("Deactivation will block new bookings but will not cancel " + affected + " existing upcoming bookings.")
                        .proceed(false)
                        .build();
            }
        }

        if (req.getIsActive() != null) {
            state.setIsActive(req.getIsActive());
        }

        return DeactivationWarningResponse.builder()
                .warning(false)
                .proceed(true)
                .updatedEntity(mapState(stateRepository.save(state)))
                .build();
    }

    @Transactional
    public void deleteState(UUID callerId, UUID stateId, boolean cascade) {
        requireAdmin(callerId);

        State state = stateRepository.findById(stateId)
                .orElseThrow(() -> new ResourceNotFoundException("State not found: " + stateId));

        long cityCount = cityRepository.countByState_StateId(stateId);
        long pincodeCount = pincodeRepository.countByState_StateId(stateId);

        if ((cityCount > 0 || pincodeCount > 0) && !cascade) {
            throw new IllegalArgumentException("Cannot delete state '" + state.getName() +
                    "' because it contains " + cityCount + " cities and " + pincodeCount +
                    " pincodes. Delete or reassign them first, or confirm cascade delete.");
        }

        if (cascade) {
            List<ServiceablePincode> pins = pincodeRepository.findAll().stream()
                    .filter(p -> p.getState() != null && stateId.equals(p.getState().getStateId()))
                    .collect(Collectors.toList());
            if (!pins.isEmpty()) {
                pincodeRepository.deleteAll(pins);
            }

            List<City> cities = cityRepository.findByState_StateIdOrderByNameAsc(stateId);
            if (!cities.isEmpty()) {
                cityRepository.deleteAll(cities);
            }
        }

        stateRepository.delete(state);
    }

    // ════════════════════════════════════════════════════════════════
    // CITY MANAGEMENT
    // ════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<CityResponse> getCities(UUID stateId) {
        List<City> cities = (stateId != null)
                ? cityRepository.findByState_StateIdOrderByNameAsc(stateId)
                : cityRepository.findAll();
        return cities.stream().map(this::mapCity).collect(Collectors.toList());
    }

    @Transactional
    public CityResponse createCity(UUID callerId, CreateCityRequest req) {
        requireAdmin(callerId);

        State state = stateRepository.findById(req.getStateId())
                .orElseThrow(() -> new ResourceNotFoundException("State not found: " + req.getStateId()));

        String norm = normalize(req.getName());
        if (cityRepository.findByState_StateIdAndNameNorm(req.getStateId(), norm).isPresent()) {
            throw new IllegalArgumentException("City '" + req.getName() + "' already exists in this state.");
        }

        City city = City.builder()
                .state(state)
                .name(req.getName().trim())
                .nameNorm(norm)
                .isActive(req.getIsActive() != null && req.getIsActive())
                .build();

        return mapCity(cityRepository.save(city));
    }

    @Transactional
    public Object updateCity(UUID callerId, UUID cityId, UpdateCityRequest req, boolean confirm) {
        requireAdmin(callerId);

        City city = cityRepository.findById(cityId)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + cityId));

        if (req.getName() != null && !req.getName().isBlank()) {
            String norm = normalize(req.getName());
            cityRepository.findByState_StateIdAndNameNorm(city.getState().getStateId(), norm).ifPresent(existing -> {
                if (!existing.getCityId().equals(cityId)) {
                    throw new IllegalArgumentException("City '" + req.getName() + "' already exists in this state.");
                }
            });
            city.setName(req.getName().trim());
            city.setNameNorm(norm);
        }

        if (req.getIsActive() != null && !req.getIsActive() && Boolean.TRUE.equals(city.getIsActive())) {
            long affected = countUpcomingBookingsForCity(cityId);
            if (affected > 0 && !confirm) {
                return DeactivationWarningResponse.builder()
                        .warning(true)
                        .affectedUpcomingBookings(affected)
                        .message("Deactivation will block new bookings but will not cancel " + affected + " existing upcoming bookings.")
                        .proceed(false)
                        .build();
            }
        }

        if (req.getIsActive() != null) {
            city.setIsActive(req.getIsActive());
        }

        return DeactivationWarningResponse.builder()
                .warning(false)
                .proceed(true)
                .updatedEntity(mapCity(cityRepository.save(city)))
                .build();
    }

    @Transactional
    public void deleteCity(UUID callerId, UUID cityId, boolean cascade) {
        requireAdmin(callerId);

        City city = cityRepository.findById(cityId)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + cityId));

        long pincodeCount = pincodeRepository.countByCity_CityId(cityId);
        if (pincodeCount > 0 && !cascade) {
            throw new IllegalArgumentException("Cannot delete city '" + city.getName() +
                    "' because it contains " + pincodeCount +
                    " pincodes. Delete or reassign them first, or confirm cascade delete.");
        }

        if (cascade && pincodeCount > 0) {
            List<ServiceablePincode> pins = pincodeRepository.findAll().stream()
                    .filter(p -> p.getCity() != null && cityId.equals(p.getCity().getCityId()))
                    .collect(Collectors.toList());
            if (!pins.isEmpty()) {
                pincodeRepository.deleteAll(pins);
            }
        }

        cityRepository.delete(city);
    }

    // ════════════════════════════════════════════════════════════════
    // PINCODE MANAGEMENT
    // ════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public PincodePage searchPincodes(UUID stateId, UUID cityId, Boolean active, String q, int page, int size) {
        String cleanQ = (q != null && !q.trim().isEmpty()) ? q.trim() : null;
        Page<ServiceablePincode> result;
        if (stateId == null && cityId == null) {
            result = pincodeRepository.searchWithoutGeo(active, cleanQ, PageRequest.of(page, size));
        } else {
            result = pincodeRepository.search(stateId, cityId, active, cleanQ, PageRequest.of(page, size));
        }
        return PincodePage.builder()
                .content(result.getContent().stream().map(this::mapPincode).collect(Collectors.toList()))
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    @Transactional
    public PincodeResponse createPincode(UUID callerId, CreatePincodeRequest req) {
        requireAdmin(callerId);
        return saveSinglePincode(req);
    }

    @Transactional
    public List<PincodeResponse> bulkCreatePincodes(UUID callerId, List<CreatePincodeRequest> reqs) {
        requireAdmin(callerId);
        return reqs.stream().map(this::saveSinglePincode).collect(Collectors.toList());
    }

    private PincodeResponse saveSinglePincode(CreatePincodeRequest req) {
        String pincode = req.getPincode() != null ? req.getPincode().trim() : null;
        if (pincode == null || !pincode.matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("Pincode must be exactly 6 digits.");
        }

        String rawArea = req.getAreaName() != null ? req.getAreaName().trim() : null;
        String areaName = (rawArea != null && !rawArea.isEmpty()) ? rawArea : null;

        // Duplicate handling: multiple localities can exist under the same PIN code,
        // but identical (pincode, locality) combinations are rejected.
        List<ServiceablePincode> existingPins = pincodeRepository.findAllByPincode(pincode);
        if (existingPins.isEmpty()) {
            pincodeRepository.findByPincode(pincode).ifPresent(existingPins::add);
        }

        String normArea = areaName != null ? areaName.toUpperCase() : "";
        boolean isDuplicate = existingPins.stream().anyMatch(p -> {
            String existingArea = p.getAreaName() != null ? p.getAreaName().trim().toUpperCase() : "";
            return existingArea.equals(normArea);
        });

        if (isDuplicate) {
            if (areaName == null) {
                throw new IllegalArgumentException("Pincode " + pincode + " already exists.");
            } else {
                throw new IllegalArgumentException("Pincode " + pincode + " with locality '" + areaName + "' already exists.");
            }
        }

        // City and State are optional metadata for future expansion
        City city = null;
        State state = null;

        if (req.getCityId() != null) {
            city = cityRepository.findById(req.getCityId())
                    .orElseThrow(() -> new ResourceNotFoundException("City not found: " + req.getCityId()));
        }

        if (req.getStateId() != null) {
            state = stateRepository.findById(req.getStateId())
                    .orElseThrow(() -> new ResourceNotFoundException("State not found: " + req.getStateId()));
        }

        // Validate city belongs to state if both are specified
        if (city != null && state != null && city.getState() != null) {
            if (!city.getState().getStateId().equals(state.getStateId())) {
                throw new IllegalArgumentException("City " + req.getCityId() + " does not belong to state " + req.getStateId());
            }
        } else if (city != null && state == null && city.getState() != null) {
            // Auto-populate state from city if state was omitted
            state = city.getState();
        }

        ServiceablePincode pincodeEntity = ServiceablePincode.builder()
                .pincode(pincode)
                .city(city)
                .state(state)
                .areaName(areaName)
                .isActive(req.getIsActive() != null && req.getIsActive())
                .build();

        return mapPincode(pincodeRepository.save(pincodeEntity));
    }

    @Transactional
    public Object updatePincode(UUID callerId, UUID pincodeId, UpdatePincodeRequest req, boolean confirm) {
        requireAdmin(callerId);

        ServiceablePincode pin = pincodeRepository.findById(pincodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Pincode not found: " + pincodeId));

        if (req.getAreaName() != null) {
            String newArea = req.getAreaName().trim().isEmpty() ? null : req.getAreaName().trim();
            String normNewArea = newArea != null ? newArea.toUpperCase() : "";

            // Check if another record with the same pincode already has this locality
            List<ServiceablePincode> others = pincodeRepository.findAllByPincode(pin.getPincode());
            if (others.isEmpty()) {
                pincodeRepository.findByPincode(pin.getPincode()).ifPresent(others::add);
            }

            boolean duplicate = others.stream()
                    .filter(p -> !p.getPincodeId().equals(pincodeId))
                    .anyMatch(p -> {
                        String existingArea = p.getAreaName() != null ? p.getAreaName().trim().toUpperCase() : "";
                        return existingArea.equals(normNewArea);
                    });

            if (duplicate) {
                if (newArea == null) {
                    throw new IllegalArgumentException("Pincode " + pin.getPincode() + " already exists.");
                } else {
                    throw new IllegalArgumentException("Pincode " + pin.getPincode() + " with locality '" + newArea + "' already exists.");
                }
            }
            pin.setAreaName(newArea);
        }

        if (req.getIsActive() != null && !req.getIsActive() && Boolean.TRUE.equals(pin.getIsActive())) {
            long affected = countUpcomingBookingsForPincode(pin.getPincode());
            if (affected > 0 && !confirm) {
                return DeactivationWarningResponse.builder()
                        .warning(true)
                        .affectedUpcomingBookings(affected)
                        .message("Disabling pincode " + pin.getPincode() + " will block new bookings but will not cancel "
                                + affected + " existing upcoming bookings.")
                        .proceed(false)
                        .build();
            }
        }

        if (req.getIsActive() != null) pin.setIsActive(req.getIsActive());

        return DeactivationWarningResponse.builder()
                .warning(false)
                .proceed(true)
                .updatedEntity(mapPincode(pincodeRepository.save(pin)))
                .build();
    }

    @Transactional
    public void deletePincode(UUID callerId, UUID pincodeId) {
        requireAdmin(callerId);

        ServiceablePincode pin = pincodeRepository.findById(pincodeId)
                .orElseThrow(() -> new ResourceNotFoundException("Pincode not found: " + pincodeId));

        pincodeRepository.delete(pin);
    }

    // ════════════════════════════════════════════════════════════════
    // CUSTOMER SERVICEABILITY CHECK (Simplified to PIN code)
    // ════════════════════════════════════════════════════════════════

    /**
     * Public — no authentication required. Returns a safe response that never
     * exposes internal IDs or administrative state to the customer.
     * Serviceability is determined directly by PIN code. City and state are
     * returned as optional metadata. Supports multiple localities.
     */
    @Transactional(readOnly = true)
    public ServiceabilityResponse checkServiceability(String rawPincode) {
        if (rawPincode == null || !rawPincode.trim().matches("^[0-9]{6}$")) {
            return ServiceabilityResponse.builder()
                    .serviceable(false)
                    .pincode(rawPincode)
                    .message("Sorry, FirstDoor is not available in your area yet.")
                    .build();
        }

        String pin = rawPincode.trim();
        List<ServiceablePincode> pins = pincodeRepository.findAllByPincode(pin);
        if (pins.isEmpty()) {
            pincodeRepository.findByPincode(pin).ifPresent(pins::add);
        }

        if (pins.isEmpty()) {
            return ServiceabilityResponse.builder()
                    .serviceable(false)
                    .pincode(pin)
                    .message("Sorry, FirstDoor is not available in your area yet.")
                    .build();
        }

        // Serviceable if at least one locality/entry under this PIN code is active
        List<ServiceablePincode> activePins = pins.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsActive()))
                .collect(Collectors.toList());

        if (activePins.isEmpty()) {
            return ServiceabilityResponse.builder()
                    .serviceable(false)
                    .pincode(pin)
                    .message("Sorry, FirstDoor is not available in your area yet.")
                    .build();
        }

        // Extract all locality names associated with this PIN code
        List<String> localities = pins.stream()
                .map(ServiceablePincode::getAreaName)
                .filter(a -> a != null && !a.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());

        String areaName = localities.isEmpty() ? null : String.join(", ", localities);

        // Optional city and state metadata
        String city = pins.stream()
                .filter(p -> p.getCity() != null)
                .map(p -> p.getCity().getName())
                .findFirst().orElse(null);

        String state = pins.stream()
                .filter(p -> p.getState() != null)
                .map(p -> p.getState().getName())
                .findFirst().orElse(null);

        return ServiceabilityResponse.builder()
                .serviceable(true)
                .pincode(pin)
                .city(city)
                .state(state)
                .areaName(areaName)
                .localities(localities)
                .message("Great! FirstDoor services are available in your area.")
                .build();
    }

    /**
     * Booking enforcement gate — throws ServiceabilityException (HTTP 422) if
     * the pincode is unknown or inactive.
     * Serviceability is determined directly by PIN code.
     * Called inside BookingStateMachine.createBooking() within the same transaction.
     */
    @Transactional(readOnly = true)
    public void assertServiceable(String rawPincode) {
        if (rawPincode == null || rawPincode.isBlank() || !rawPincode.trim().matches("^[0-9]{6}$")) {
            throw new ServiceabilityException(
                    "Sorry, FirstDoor is not available in your area yet. A valid 6-digit serviceable pincode is required.");
        }

        String pin = rawPincode.trim();
        List<ServiceablePincode> pins = pincodeRepository.findAllByPincode(pin);
        if (pins.isEmpty()) {
            pincodeRepository.findByPincode(pin).ifPresent(pins::add);
        }

        if (pins.isEmpty()) {
            throw new ServiceabilityException(
                    "Sorry, FirstDoor is not available in your area yet. Pincode: " + pin);
        }

        boolean hasActive = pins.stream().anyMatch(p -> Boolean.TRUE.equals(p.getIsActive()));
        if (!hasActive) {
            throw new ServiceabilityException(
                    "Sorry, FirstDoor is not available in your area yet. Pincode: " + pin);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // DEACTIVATION IMPACT HELPERS
    // ════════════════════════════════════════════════════════════════

    private long countUpcomingBookingsForPincode(String pincode) {
        // Count bookings that are SEARCHING or ACCEPTED with scheduledAt in the future.
        // We don't have pincode on booking; we approximate via lat/lng area match.
        // As a safe over-estimate we count all non-terminal scheduled bookings.
        return bookingRepository.findByStatus(BookingStage.SEARCHING).size()
             + bookingRepository.findByStatus(BookingStage.ACCEPTED).size();
    }

    private long countUpcomingBookingsForCity(UUID cityId) {
        return countUpcomingBookingsForPincode(null); // same safe over-estimate
    }

    private long countUpcomingBookingsForState(UUID stateId) {
        return countUpcomingBookingsForPincode(null);
    }

    // ════════════════════════════════════════════════════════════════
    // MAPPING HELPERS
    // ════════════════════════════════════════════════════════════════

    private String normalize(String name) {
        return name == null ? "" : name.trim().toUpperCase();
    }

    private StateResponse mapState(State s) {
        return StateResponse.builder()
                .stateId(s.getStateId())
                .name(s.getName())
                .isActive(s.getIsActive())
                .cityCount(cityRepository.countByState_StateId(s.getStateId()))
                .pincodeCount(pincodeRepository.countByState_StateId(s.getStateId()))
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    private CityResponse mapCity(City c) {
        return CityResponse.builder()
                .cityId(c.getCityId())
                .stateId(c.getState().getStateId())
                .stateName(c.getState().getName())
                .name(c.getName())
                .isActive(c.getIsActive())
                .pincodeCount(pincodeRepository.countByCity_CityId(c.getCityId()))
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private PincodeResponse mapPincode(ServiceablePincode p) {
        return PincodeResponse.builder()
                .pincodeId(p.getPincodeId())
                .pincode(p.getPincode())
                .cityId(p.getCity() != null ? p.getCity().getCityId() : null)
                .cityName(p.getCity() != null ? p.getCity().getName() : null)
                .stateId(p.getState() != null ? p.getState().getStateId() : null)
                .stateName(p.getState() != null ? p.getState().getName() : null)
                .areaName(p.getAreaName())
                .isActive(p.getIsActive())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
