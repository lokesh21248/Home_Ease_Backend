package com.homeease.backend.service;

import com.homeease.backend.dto.ServiceAreaDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.exception.ServiceabilityException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceAreaServiceTest {

    @Mock
    private StateRepository stateRepository;

    @Mock
    private CityRepository cityRepository;

    @Mock
    private ServiceablePincodeRepository pincodeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private ServiceAreaService serviceAreaService;

    private UUID adminId;
    private UUID customerId;
    private User adminUser;
    private User customerUser;
    private State telanganaState;
    private City hyderabadCity;
    private ServiceablePincode pincode500072;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        customerId = UUID.randomUUID();

        adminUser = User.builder()
                .userId(adminId)
                .email("admin@firstdoor.com")
                .role(UserRole.ADMIN)
                .build();

        customerUser = User.builder()
                .userId(customerId)
                .email("customer@firstdoor.com")
                .role(UserRole.CUSTOMER)
                .build();

        telanganaState = State.builder()
                .stateId(UUID.randomUUID())
                .name("Telangana")
                .nameNorm("TELANGANA")
                .isActive(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        hyderabadCity = City.builder()
                .cityId(UUID.randomUUID())
                .state(telanganaState)
                .name("Hyderabad")
                .nameNorm("HYDERABAD")
                .isActive(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        pincode500072 = ServiceablePincode.builder()
                .pincodeId(UUID.randomUUID())
                .pincode("500072")
                .city(hyderabadCity)
                .state(telanganaState)
                .areaName("Kukatpally")
                .isActive(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. AUTHORIZATION TESTS
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin can create state")
    void testCreateState_AdminAuthorized_Success() {
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));
        when(stateRepository.findByNameNorm("TELANGANA")).thenReturn(Optional.empty());
        when(stateRepository.save(any(State.class))).thenAnswer(i -> {
            State s = i.getArgument(0);
            s.setStateId(UUID.randomUUID());
            return s;
        });

        CreateStateRequest req = CreateStateRequest.builder()
                .name("Telangana")
                .isActive(true)
                .build();

        StateResponse res = serviceAreaService.createState(adminId, req);
        assertNotNull(res);
        assertEquals("Telangana", res.getName());
        assertTrue(res.getIsActive());
    }

    @Test
    @DisplayName("Customer cannot create state (throws SecurityException)")
    void testCreateState_CustomerUnauthorized_ThrowsSecurityException() {
        when(userRepository.findById(customerId)).thenReturn(Optional.of(customerUser));

        CreateStateRequest req = CreateStateRequest.builder()
                .name("Telangana")
                .isActive(true)
                .build();

        assertThrows(SecurityException.class, () -> serviceAreaService.createState(customerId, req));
        verify(stateRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. DUPLICATION & VALIDATION TESTS
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Duplicate state name is rejected case-insensitively")
    void testCreateState_DuplicateName_ThrowsException() {
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));
        when(stateRepository.findByNameNorm("TELANGANA")).thenReturn(Optional.of(telanganaState));

        CreateStateRequest req = CreateStateRequest.builder()
                .name("telangana") // lowercase
                .isActive(true)
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> serviceAreaService.createState(adminId, req));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @DisplayName("Duplicate city in same state is rejected")
    void testCreateCity_DuplicateInSameState_ThrowsException() {
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));
        when(stateRepository.findById(telanganaState.getStateId())).thenReturn(Optional.of(telanganaState));
        when(cityRepository.findByState_StateIdAndNameNorm(telanganaState.getStateId(), "HYDERABAD"))
                .thenReturn(Optional.of(hyderabadCity));

        CreateCityRequest req = CreateCityRequest.builder()
                .stateId(telanganaState.getStateId())
                .name("Hyderabad")
                .build();

        assertThrows(IllegalArgumentException.class, () -> serviceAreaService.createCity(adminId, req));
    }

    @Test
    @DisplayName("City creation fails if parent state does not exist")
    void testCreateCity_StateNotFound_ThrowsResourceNotFound() {
        UUID unknownStateId = UUID.randomUUID();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));
        when(stateRepository.findById(unknownStateId)).thenReturn(Optional.empty());

        CreateCityRequest req = CreateCityRequest.builder()
                .stateId(unknownStateId)
                .name("Warangal")
                .build();

        assertThrows(ResourceNotFoundException.class, () -> serviceAreaService.createCity(adminId, req));
    }

    @Test
    @DisplayName("Pincode creation fails if city does not belong to state")
    void testCreatePincode_MismatchedCityState_ThrowsException() {
        State karnataka = State.builder()
                .stateId(UUID.randomUUID())
                .name("Karnataka")
                .nameNorm("KARNATAKA")
                .isActive(true)
                .build();

        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));
        when(pincodeRepository.findByPincode("500072")).thenReturn(Optional.empty());
        when(cityRepository.findById(hyderabadCity.getCityId())).thenReturn(Optional.of(hyderabadCity)); // in Telangana
        when(stateRepository.findById(karnataka.getStateId())).thenReturn(Optional.of(karnataka)); // Karnataka

        CreatePincodeRequest req = CreatePincodeRequest.builder()
                .pincode("500072")
                .cityId(hyderabadCity.getCityId())
                .stateId(karnataka.getStateId())
                .build();

        assertThrows(IllegalArgumentException.class, () -> serviceAreaService.createPincode(adminId, req));
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. SERVICEABILITY HIERARCHY TESTS
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Active pincode under active city and state is serviceable")
    void testCheckServiceability_AllActive_ReturnsTrue() {
        when(pincodeRepository.findByPincode("500072")).thenReturn(Optional.of(pincode500072));

        ServiceabilityResponse res = serviceAreaService.checkServiceability("500072");

        assertTrue(res.isServiceable());
        assertEquals("500072", res.getPincode());
        assertEquals("Hyderabad", res.getCity());
        assertEquals("Telangana", res.getState());
        assertEquals("Great! FirstDoor services are available in your area.", res.getMessage());
    }

    @Test
    @DisplayName("Inactive pincode is not serviceable")
    void testCheckServiceability_InactivePincode_ReturnsFalse() {
        pincode500072.setIsActive(false);
        when(pincodeRepository.findByPincode("500072")).thenReturn(Optional.of(pincode500072));

        ServiceabilityResponse res = serviceAreaService.checkServiceability("500072");

        assertFalse(res.isServiceable());
        assertEquals("Sorry, FirstDoor is not available in your area yet.", res.getMessage());
    }

    @Test
    @DisplayName("Active pincode under inactive city is not serviceable")
    void testCheckServiceability_InactiveCity_ReturnsFalse() {
        hyderabadCity.setIsActive(false); // deactivated city
        when(pincodeRepository.findByPincode("500072")).thenReturn(Optional.of(pincode500072));

        ServiceabilityResponse res = serviceAreaService.checkServiceability("500072");

        assertFalse(res.isServiceable());
        assertEquals("Sorry, FirstDoor is not available in your area yet.", res.getMessage());
    }

    @Test
    @DisplayName("Active pincode under inactive state is not serviceable")
    void testCheckServiceability_InactiveState_ReturnsFalse() {
        telanganaState.setIsActive(false); // deactivated state
        when(pincodeRepository.findByPincode("500072")).thenReturn(Optional.of(pincode500072));

        ServiceabilityResponse res = serviceAreaService.checkServiceability("500072");

        assertFalse(res.isServiceable());
        assertEquals("Sorry, FirstDoor is not available in your area yet.", res.getMessage());
    }

    @Test
    @DisplayName("Unknown pincode returns safe not-serviceable response without leaking internal details")
    void testCheckServiceability_UnknownPincode_ReturnsFalse() {
        when(pincodeRepository.findByPincode("999999")).thenReturn(Optional.empty());

        ServiceabilityResponse res = serviceAreaService.checkServiceability("999999");

        assertFalse(res.isServiceable());
        assertEquals("999999", res.getPincode());
        assertNull(res.getCity());
        assertNull(res.getState());
        assertEquals("Sorry, FirstDoor is not available in your area yet.", res.getMessage());
    }

    @Test
    @DisplayName("Invalid pincode format (non-6 digits) returns safe unavailable response")
    void testCheckServiceability_InvalidFormat_ReturnsFalse() {
        ServiceabilityResponse res1 = serviceAreaService.checkServiceability("123");
        assertFalse(res1.isServiceable());

        ServiceabilityResponse res2 = serviceAreaService.checkServiceability("ABCDEF");
        assertFalse(res2.isServiceable());

        ServiceabilityResponse res3 = serviceAreaService.checkServiceability(null);
        assertFalse(res3.isServiceable());
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. BOOKING ENFORCEMENT GATE TESTS
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("assertServiceable passes when full chain is active")
    void testAssertServiceable_ValidChain_NoException() {
        when(pincodeRepository.findByPincode("500072")).thenReturn(Optional.of(pincode500072));

        assertDoesNotThrow(() -> serviceAreaService.assertServiceable("500072"));
    }

    @Test
    @DisplayName("assertServiceable throws ServiceabilityException when pincode is null or blank")
    void testAssertServiceable_NullOrBlank_ThrowsException() {
        assertThrows(ServiceabilityException.class, () -> serviceAreaService.assertServiceable(null));
        assertThrows(ServiceabilityException.class, () -> serviceAreaService.assertServiceable(""));
        assertThrows(ServiceabilityException.class, () -> serviceAreaService.assertServiceable("  "));
    }

    @Test
    @DisplayName("assertServiceable throws ServiceabilityException when pincode is inactive")
    void testAssertServiceable_InactivePincode_ThrowsException() {
        pincode500072.setIsActive(false);
        when(pincodeRepository.findByPincode("500072")).thenReturn(Optional.of(pincode500072));

        ServiceabilityException ex = assertThrows(ServiceabilityException.class,
                () -> serviceAreaService.assertServiceable("500072"));
        assertTrue(ex.getMessage().contains("FirstDoor is not available in your area yet"));
    }

    @Test
    @DisplayName("assertServiceable throws ServiceabilityException when city is inactive")
    void testAssertServiceable_InactiveCity_ThrowsException() {
        hyderabadCity.setIsActive(false);
        when(pincodeRepository.findByPincode("500072")).thenReturn(Optional.of(pincode500072));

        ServiceabilityException ex = assertThrows(ServiceabilityException.class,
                () -> serviceAreaService.assertServiceable("500072"));
        assertTrue(ex.getMessage().contains("not currently available in Hyderabad"));
    }

    @Test
    @DisplayName("assertServiceable throws ServiceabilityException when state is inactive")
    void testAssertServiceable_InactiveState_ThrowsException() {
        telanganaState.setIsActive(false);
        when(pincodeRepository.findByPincode("500072")).thenReturn(Optional.of(pincode500072));

        ServiceabilityException ex = assertThrows(ServiceabilityException.class,
                () -> serviceAreaService.assertServiceable("500072"));
        assertTrue(ex.getMessage().contains("not currently available in Telangana"));
    }

    // ─────────────────────────────────────────────────────────────────
    // 5. DEACTIVATION SAFETY & BOOKING WARNING TESTS
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Deactivating state with upcoming bookings returns warning when confirm is false")
    void testUpdateState_DeactivateWithUpcomingBookings_ReturnsWarning() {
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));
        when(stateRepository.findById(telanganaState.getStateId())).thenReturn(Optional.of(telanganaState));
        when(bookingRepository.findByStatus(BookingStage.SEARCHING)).thenReturn(List.of(new Booking()));
        when(bookingRepository.findByStatus(BookingStage.ACCEPTED)).thenReturn(List.of(new Booking()));

        UpdateStateRequest req = UpdateStateRequest.builder()
                .isActive(false)
                .build();

        Object result = serviceAreaService.updateState(adminId, telanganaState.getStateId(), req, false);

        assertTrue(result instanceof DeactivationWarningResponse);
        DeactivationWarningResponse warning = (DeactivationWarningResponse) result;
        assertTrue(warning.isWarning());
        assertFalse(warning.isProceed());
        assertEquals(2, warning.getAffectedUpcomingBookings());
        verify(stateRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deactivating state with upcoming bookings succeeds when confirm is true")
    void testUpdateState_DeactivateWithUpcomingBookings_Confirmed_Success() {
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));
        when(stateRepository.findById(telanganaState.getStateId())).thenReturn(Optional.of(telanganaState));
        when(stateRepository.save(any(State.class))).thenAnswer(i -> i.getArgument(0));

        UpdateStateRequest req = UpdateStateRequest.builder()
                .isActive(false)
                .build();

        Object result = serviceAreaService.updateState(adminId, telanganaState.getStateId(), req, true);

        assertTrue(result instanceof DeactivationWarningResponse);
        DeactivationWarningResponse res = (DeactivationWarningResponse) result;
        assertFalse(res.isWarning());
        assertTrue(res.isProceed());
        assertFalse(telanganaState.getIsActive());
        verify(stateRepository).save(telanganaState);
    }

    // ─────────────────────────────────────────────────────────────────
    // 6. SEARCH & PAGINATION TESTS
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Search pincodes returns paginated response")
    void testSearchPincodes_ReturnsPage() {
        Page<ServiceablePincode> mockPage = new PageImpl<>(List.of(pincode500072), PageRequest.of(0, 10), 1);
        when(pincodeRepository.search(eq(telanganaState.getStateId()), isNull(), eq(true), eq("500"), any()))
                .thenReturn(mockPage);

        PincodePage page = serviceAreaService.searchPincodes(telanganaState.getStateId(), null, true, "500", 0, 10);

        assertNotNull(page);
        assertEquals(1, page.getTotalElements());
        assertEquals(1, page.getContent().size());
        assertEquals("500072", page.getContent().get(0).getPincode());
    }
}
