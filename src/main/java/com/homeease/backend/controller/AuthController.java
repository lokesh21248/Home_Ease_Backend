package com.homeease.backend.controller;

import com.homeease.backend.dto.AuthDto.*;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping({"/api/v1", "/api"})
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
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

    @PostMapping("/users/register")
    public ResponseEntity<AuthTokenResponse> registerUser(@Valid @RequestBody RegisterUserRequest request) {
        return ResponseEntity.ok(authService.registerUser(request));
    }

    @PostMapping("/admin/auth/login")
    public ResponseEntity<AuthTokenResponse> adminLogin(@Valid @RequestBody AdminLoginRequest request) {
        return ResponseEntity.ok(authService.authenticateAdmin(request));
    }

    @GetMapping("/users/me")
    public ResponseEntity<User> getCurrentUser(@RequestHeader("X-User-Id") UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(user);
    }
}
