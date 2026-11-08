package com.homeease.backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.AuthDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.service.AuthService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping({"/api/v1", "/api"})
@CrossOrigin(origins = "*")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final UserRepository userRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final com.homeease.backend.security.JwtService jwtService;
    private final com.homeease.backend.service.UserAddressService userAddressService;

    public AuthController(AuthService authService,
                          UserRepository userRepository,
                          RedisTemplate<String, String> redisTemplate,
                          ObjectMapper objectMapper,
                          com.homeease.backend.security.JwtService jwtService,
                          com.homeease.backend.service.UserAddressService userAddressService) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
        this.userAddressService = userAddressService;
    }

    private UUID resolveUserId(String authHeader, UUID headerUserId, UUID queryUserId) {
        if (queryUserId != null) return queryUserId;
        if (headerUserId != null) return headerUserId;
        if (authHeader != null && !authHeader.isBlank()) {
            try {
                String clean = authHeader.replace("Bearer ", "").trim();
                return UUID.fromString(clean);
            } catch (Exception ignored) {
                return jwtService.extractUserId(authHeader);
            }
        }
        return null;
    }

    @PostMapping("/auth/firebase-login")
    public ResponseEntity<AuthTokenResponse> firebaseLogin(@Valid @RequestBody FirebaseLoginRequest request) {
        return ResponseEntity.ok(authService.authenticateFirebaseToken(request));
    }

    @PostMapping("/auth/otp/send")
    public ResponseEntity<SendOtpResponse> sendOtp(@Valid @RequestBody SendOtpRequest request) {
        return ResponseEntity.ok(authService.sendOtp(request));
    }

    @PostMapping("/auth/otp/verify")
    public ResponseEntity<ApiResponse<AuthTokenResponse>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        AuthTokenResponse response = authService.verifyOtp(request);
        return ResponseEntity.ok(ApiResponse.<AuthTokenResponse>builder()
                .status("SUCCESS")
                .data(response)
                .build());
    }

    @PostMapping({"/users/register", "/user/register", "/auth/register"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @Valid @RequestBody RegisterUserRequest request) {
        if (request.getUserId() == null) {
            UUID resolved = resolveUserId(authHeader, headerUserId, queryUserId);
            if (resolved != null) {
                request.setUserId(resolved);
            }
        }
        User user = authService.registerUser(request, authHeader);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(
                ApiResponse.<Map<String, Object>>builder()
                        .status("SUCCESS")
                        .message("Worker account registered successfully.")
                        .data(Map.of("userId", user.getUserId().toString()))
                        .build()
        );
    }

    @PostMapping("/admin/auth/login")
    public ResponseEntity<AuthTokenResponse> adminLogin(@Valid @RequestBody AdminLoginRequest request) {
        return ResponseEntity.ok(authService.authenticateAdmin(request));
    }

    // --- User Profile Endpoints (Supports both singular /user and plural /users) ---

    @GetMapping({"/users/me", "/user/me", "/user/profile", "/users/profile"})
    public ResponseEntity<User> getCurrentUser(
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        if (userId == null) {
            throw new ResourceNotFoundException("Missing userId. Please provide userId in query param (?userId=...), header (X-User-Id), or Bearer token.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + userId));
        return ResponseEntity.ok(user);
    }

    @PutMapping({"/user/profile", "/users/profile", "/user/me", "/users/me"})
    public ResponseEntity<User> updateUserProfile(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestBody Map<String, Object> updates) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        if (userId == null) {
            throw new ResourceNotFoundException("Missing userId. Please provide Bearer token or X-User-Id header.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + userId));

        if (updates.containsKey("fullName") && updates.get("fullName") != null) {
            user.setFullName(updates.get("fullName").toString());
        }
        if (updates.containsKey("email") && updates.get("email") != null) {
            user.setEmail(updates.get("email").toString());
        }

        return ResponseEntity.ok(userRepository.save(user));
    }

    @PostMapping({"/user/fcm-token", "/users/fcm-token"})
    public ResponseEntity<Map<String, String>> registerFcmToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestBody Map<String, String> request) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        String fcmToken = request.get("fcmToken");
        if (userId != null && fcmToken != null && !fcmToken.isBlank()) {
            try {
                redisTemplate.opsForValue().set("fcm_token:" + userId, fcmToken);
            } catch (Exception ignored) {}
            logger.info("Registered FCM token for user {}: {}", userId, fcmToken);
        }
        return ResponseEntity.ok(Map.of("message", "FCM token registered successfully."));
    }

    // --- User Address Endpoints (Supports both singular /user, plural /users, and /users/me/addresses) ---

    @GetMapping({"/user/addresses", "/users/addresses", "/users/me/addresses", "/user/me/addresses"})
    public ResponseEntity<List<Map<String, Object>>> getUserAddresses(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        if (userId == null) {
            userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        }
        List<Map<String, Object>> addresses = userAddressService.getUserAddresses(userId);
        return ResponseEntity.ok(addresses);
    }

    @PostMapping({"/user/addresses", "/users/addresses", "/users/me/addresses", "/user/me/addresses"})
    public ResponseEntity<Map<String, Object>> saveUserAddress(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestBody Map<String, Object> payload) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        if (userId == null && payload != null && payload.containsKey("userId") && payload.get("userId") != null) {
            try {
                userId = UUID.fromString(payload.get("userId").toString());
            } catch (Exception ignored) {}
        }
        if (userId == null) {
            userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        }

        Map<String, Object> saved = userAddressService.saveUserAddress(userId, payload);
        return ResponseEntity.ok(saved);
    }

    @PutMapping({"/user/addresses/{addressId}", "/users/addresses/{addressId}", "/users/me/addresses/{addressId}", "/user/me/addresses/{addressId}"})
    public ResponseEntity<Map<String, Object>> updateUserAddress(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @PathVariable("addressId") String addressId,
            @RequestBody Map<String, Object> payload) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        if (userId == null) {
            userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        }

        Map<String, Object> updated = userAddressService.updateUserAddress(userId, addressId, payload);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping({"/user/addresses/{addressId}", "/users/addresses/{addressId}", "/users/me/addresses/{addressId}", "/user/me/addresses/{addressId}"})
    public ResponseEntity<Map<String, String>> deleteUserAddress(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-User-Id", required = false) UUID headerUserId,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @PathVariable("addressId") String addressId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        if (userId == null) {
            userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        }
        userAddressService.deleteUserAddress(userId, addressId);
        return ResponseEntity.ok(Map.of("message", "Address deleted successfully."));
    }
}
