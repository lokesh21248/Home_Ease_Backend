package com.homeease.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homeease.backend.model.enums.UserRole;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.Map;

public class AuthDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApiResponse<T> {
        @Builder.Default
        private String status = "SUCCESS";
        private String message;
        private T data;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FirebaseLoginRequest {
        @NotBlank(message = "Firebase ID Token is required")
        private String firebaseToken;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AdminLoginRequest {
        @NotBlank(message = "Email is required")
        private String email;
        @NotBlank(message = "Password is required")
        private String password;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RegisterUserRequest {
        private java.util.UUID userId;
        @NotBlank(message = "Full name is required")
        private String fullName;
        private String phoneNumber;
        private String email;
        private UserRole role; // CUSTOMER or WORKER
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SendOtpRequest {
        @NotBlank(message = "Phone number is required")
        private String phoneNumber;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SendOtpData {
        private Boolean isNewUser;
        private Boolean userExists;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SendOtpResponse {
        @Builder.Default
        private String status = "SUCCESS";
        private String message;
        private String otpId;
        private SendOtpData data;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VerifyOtpRequest {
        @NotBlank(message = "Phone number is required")
        private String phoneNumber;
        @NotBlank(message = "OTP code is required")
        private String otpCode;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuthTokenResponse {
        private String token;
        private String userId;
        private String fullName;
        private String phoneNumber;
        private String email;
        private UserRole role;

        // Flags for multi-step onboarding flow
        private Boolean isNewUser;
        private Boolean userExists;
        private Boolean isRegistered;
        private Boolean isKycCompleted;
        private Boolean isVerified;
    }
}
