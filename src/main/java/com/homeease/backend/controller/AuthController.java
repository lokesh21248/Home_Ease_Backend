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
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final UserRepository userRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // In-memory fallback if Redis is not yet configured
    private final Map<UUID, List<Map<String, Object>>> addressFallbackStore = new ConcurrentHashMap<>();

    public AuthController(AuthService authService,
                          UserRepository userRepository,
                          RedisTemplate<String, String> redisTemplate,
                          ObjectMapper objectMapper) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
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
    public ResponseEntity<AuthTokenResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return ResponseEntity.ok(authService.verifyOtp(request));
    }

    @PostMapping({"/users/register", "/user/register", "/auth/register"})
    public ResponseEntity<AuthTokenResponse> registerUser(@Valid @RequestBody RegisterUserRequest request) {
        return ResponseEntity.ok(authService.registerUser(request));
    }

    @PostMapping("/admin/auth/login")
    public ResponseEntity<AuthTokenResponse> adminLogin(@Valid @RequestBody AdminLoginRequest request) {
        return ResponseEntity.ok(authService.authenticateAdmin(request));
    }

    // --- User Profile Endpoints (Supports both singular /user and plural /users) ---

    @GetMapping({"/users/me", "/user/me", "/user/profile", "/users/profile"})
    public ResponseEntity<User> getCurrentUser(@RequestHeader("X-User-Id") UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + userId));
        return ResponseEntity.ok(user);
    }

    @PutMapping({"/user/profile", "/users/profile", "/user/me", "/users/me"})
    public ResponseEntity<User> updateUserProfile(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestBody Map<String, Object> updates) {
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
            @RequestHeader("X-User-Id") UUID userId,
            @RequestBody Map<String, String> request) {
        String fcmToken = request.get("fcmToken");
        if (fcmToken != null && !fcmToken.isBlank()) {
            try {
                redisTemplate.opsForValue().set("fcm_token:" + userId, fcmToken);
            } catch (Exception ignored) {}
            logger.info("Registered FCM token for user {}: {}", userId, fcmToken);
        }
        return ResponseEntity.ok(Map.of("message", "FCM token registered successfully."));
    }

    // --- User Address Endpoints (Supports both singular /user and plural /users) ---

    @GetMapping({"/user/addresses", "/users/addresses"})
    public ResponseEntity<List<Map<String, Object>>> getUserAddresses(@RequestHeader("X-User-Id") UUID userId) {
        List<Map<String, Object>> addresses = loadAddresses(userId);

        if (addresses.isEmpty()) {
            // Default seed address if user has not yet saved any custom address
            Map<String, Object> defaultAddr = new LinkedHashMap<>();
            defaultAddr.put("id", UUID.randomUUID().toString());
            defaultAddr.put("userId", userId.toString());
            defaultAddr.put("title", "Home");
            defaultAddr.put("addressLine", "Flat 402, Green Valley Apartments, Hitech City");
            defaultAddr.put("city", "Hyderabad");
            defaultAddr.put("lat", 17.448293);
            defaultAddr.put("lng", 78.374182);
            defaultAddr.put("isDefault", true);

            addresses = List.of(defaultAddr);
        }

        return ResponseEntity.ok(addresses);
    }

    @PostMapping({"/user/addresses", "/users/addresses"})
    public ResponseEntity<Map<String, Object>> saveUserAddress(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestBody Map<String, Object> payload) {
        Map<String, Object> newAddress = new LinkedHashMap<>(payload);
        if (!newAddress.containsKey("id") || newAddress.get("id") == null) {
            newAddress.put("id", UUID.randomUUID().toString());
        }
        newAddress.put("userId", userId.toString());

        List<Map<String, Object>> currentList = new ArrayList<>(loadAddresses(userId));
        currentList.add(newAddress);
        persistAddresses(userId, currentList);

        return ResponseEntity.ok(newAddress);
    }

    @DeleteMapping({"/user/addresses/{addressId}", "/users/addresses/{addressId"})
    public ResponseEntity<Map<String, String>> deleteUserAddress(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable("addressId") String addressId) {
        List<Map<String, Object>> currentList = new ArrayList<>(loadAddresses(userId));
        currentList.removeIf(a -> Objects.equals(a.get("id"), addressId));
        persistAddresses(userId, currentList);

        return ResponseEntity.ok(Map.of("message", "Address deleted successfully."));
    }

    private List<Map<String, Object>> loadAddresses(UUID userId) {
        try {
            String json = redisTemplate.opsForValue().get("user_addresses:" + userId);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception ignored) {}
        return addressFallbackStore.getOrDefault(userId, Collections.emptyList());
    }

    private void persistAddresses(UUID userId, List<Map<String, Object>> addresses) {
        addressFallbackStore.put(userId, addresses);
        try {
            String json = objectMapper.writeValueAsString(addresses);
            redisTemplate.opsForValue().set("user_addresses:" + userId, json);
        } catch (Exception ignored) {}
    }
}
