package com.homeease.backend.controller;

import com.homeease.backend.dto.ServiceAreaDto.*;
import com.homeease.backend.service.ServiceAreaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ServiceAreaController.class)
@AutoConfigureMockMvc(addFilters = false) // disables spring security filters to isolate controller logic
class ServiceAreaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ServiceAreaService serviceAreaService;

    // ─────────────────────────────────────────────────────────────────
    // CUSTOMER PUBLIC ENDPOINTS
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Public serviceability check /api/v1/serviceability/check returns 200 without auth")
    void testCheckServiceability_V1_Public_Success() throws Exception {
        ServiceabilityResponse mockResponse = ServiceabilityResponse.builder()
                .serviceable(true)
                .pincode("500072")
                .city("Hyderabad")
                .state("Telangana")
                .message("Great! FirstDoor services are available in your area.")
                .build();

        when(serviceAreaService.checkServiceability("500072")).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/serviceability/check")
                        .param("pincode", "500072"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceable").value(true))
                .andExpect(jsonPath("$.pincode").value("500072"))
                .andExpect(jsonPath("$.city").value("Hyderabad"))
                .andExpect(jsonPath("$.state").value("Telangana"));
    }

    @Test
    @DisplayName("Public serviceability check legacy alias /api/serviceability/check returns 200")
    void testCheckServiceability_LegacyAlias_Success() throws Exception {
        ServiceabilityResponse mockResponse = ServiceabilityResponse.builder()
                .serviceable(false)
                .pincode("999999")
                .message("Sorry, FirstDoor is not available in your area yet.")
                .build();

        when(serviceAreaService.checkServiceability("999999")).thenReturn(mockResponse);

        mockMvc.perform(get("/api/serviceability/check")
                        .param("pincode", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceable").value(false))
                .andExpect(jsonPath("$.message").value("Sorry, FirstDoor is not available in your area yet."));
    }

    // ─────────────────────────────────────────────────────────────────
    // ADMIN ENDPOINTS
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin Overview GET /api/v1/admin/service-areas returns 200")
    void testGetOverview_Success() throws Exception {
        ServiceAreaStats stats = ServiceAreaStats.builder()
                .totalStates(1)
                .totalCities(1)
                .totalPincodes(3)
                .activePincodes(2)
                .inactivePincodes(1)
                .build();

        ServiceAreaOverviewResponse response = ServiceAreaOverviewResponse.builder()
                .stats(stats)
                .states(List.of())
                .build();

        when(serviceAreaService.getOverview()).thenReturn(response);

        mockMvc.perform(get("/api/v1/admin/service-areas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.totalStates").value(1))
                .andExpect(jsonPath("$.stats.activePincodes").value(2));
    }

    @Test
    @DisplayName("Admin createState POST /api/v1/admin/service-areas/states succeeds with X-User-Id")
    void testCreateState_Admin_Success() throws Exception {
        UUID adminId = UUID.randomUUID();
        StateResponse response = StateResponse.builder()
                .stateId(UUID.randomUUID())
                .name("Telangana")
                .isActive(true)
                .build();

        when(serviceAreaService.createState(eq(adminId), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/service-areas/states")
                        .header("X-User-Id", adminId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Telangana\", \"isActive\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Telangana"))
                .andExpect(jsonPath("$.isActive").value(true));
    }
}
