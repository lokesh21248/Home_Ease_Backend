package com.homeease.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ServiceAreaDto {

    // ─────────────────────────────────────────────
    // STATE DTOs
    // ─────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateStateRequest {
        @NotBlank(message = "State name is required")
        private String name;
        private Boolean isActive;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UpdateStateRequest {
        private String name;       // null = don't change
        private Boolean isActive;  // null = don't change
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class StateResponse {
        private UUID    stateId;
        private String  name;
        private Boolean isActive;
        private long    cityCount;
        private long    pincodeCount;
        private Instant createdAt;
        private Instant updatedAt;
    }

    // ─────────────────────────────────────────────
    // CITY DTOs
    // ─────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateCityRequest {
        @NotNull(message = "State ID is required")
        private UUID stateId;
        @NotBlank(message = "City name is required")
        private String name;
        private Boolean isActive;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UpdateCityRequest {
        private String  name;
        private Boolean isActive;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CityResponse {
        private UUID    cityId;
        private UUID    stateId;
        private String  stateName;
        private String  name;
        private Boolean isActive;
        private long    pincodeCount;
        private Instant createdAt;
        private Instant updatedAt;
    }

    // ─────────────────────────────────────────────
    // PINCODE DTOs
    // ─────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreatePincodeRequest {
        @NotBlank(message = "Pincode is required")
        @Pattern(regexp = "^[0-9]{6}$", message = "Pincode must be exactly 6 digits")
        private String pincode;
        private UUID   cityId;     // Optional metadata for future expansion
        private UUID   stateId;    // Optional metadata for future expansion
        private String areaName;   // Locality name (multiple localities can share the same PIN code)
        private Boolean isActive;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class UpdatePincodeRequest {
        private String  areaName;
        private Boolean isActive;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PincodeResponse {
        private UUID    pincodeId;
        private String  pincode;
        private UUID    cityId;
        private String  cityName;
        private UUID    stateId;
        private String  stateName;
        private String  areaName;
        private Boolean isActive;
        private Instant createdAt;
        private Instant updatedAt;
    }

    // ─────────────────────────────────────────────
    // OVERVIEW / STATS DTOs
    // ─────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ServiceAreaStats {
        private long totalStates;
        private long totalCities;
        private long totalPincodes;
        private long activePincodes;
        private long inactivePincodes;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ServiceAreaOverviewResponse {
        private ServiceAreaStats       stats;
        private List<StateResponse>    states;
    }

    // ─────────────────────────────────────────────
    // DEACTIVATION WARNING DTO
    // ─────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DeactivationWarningResponse {
        private boolean warning;
        private long    affectedUpcomingBookings;
        private String  message;
        /** Client must resend with ?confirm=true to actually deactivate. */
        private boolean proceed;
        private Object  updatedEntity; // the updated entity if proceed=true
    }

    // ─────────────────────────────────────────────
    // CUSTOMER SERVICEABILITY CHECK
    // ─────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ServiceabilityResponse {
        private boolean serviceable;
        private String  pincode;
        private String  city;     // Optional metadata; null if not configured
        private String  state;    // Optional metadata; null if not configured
        /** Human-readable locality label; joined if multiple localities. */
        private String  areaName;
        /** List of all localities associated with this PIN code. */
        private List<String> localities;
        private String  message;
    }

    // ─────────────────────────────────────────────
    // PAGINATED PINCODE RESPONSE
    // ─────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PincodePage {
        private List<PincodeResponse> content;
        private int    page;
        private int    size;
        private long   totalElements;
        private int    totalPages;
    }
}
