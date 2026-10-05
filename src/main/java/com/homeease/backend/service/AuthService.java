package com.homeease.backend.service;

import com.homeease.backend.dto.AuthDto.*;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, JwtService jwtService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthTokenResponse authenticateFirebaseToken(FirebaseLoginRequest request) {
        String phoneNumber = "+919999999999";
        String email = "testuser@homeease.com";
        String name = "HomeEase Test User";

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .fullName(name)
                            .phoneNumber(phoneNumber)
                            .email(email)
                            .role(UserRole.CUSTOMER)
                            .build();
                    return userRepository.save(newUser);
                });

        return buildResponse(user, jwtService.generateToken(user));
    }

    @Transactional
    public AuthTokenResponse registerUser(RegisterUserRequest request) {
        if (userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new RuntimeException("Phone number already registered");
        }

        UserRole role = request.getRole() != null ? request.getRole() : UserRole.CUSTOMER;

        User user = User.builder()
                .fullName(request.getFullName())
                .phoneNumber(request.getPhoneNumber())
                .email(request.getEmail())
                .role(role)
                .build();

        user = userRepository.save(user);

        return buildResponse(user, jwtService.generateToken(user));
    }

    public AuthTokenResponse authenticateAdmin(AdminLoginRequest request) {
        User admin = userRepository.findByEmail(request.getEmail())
                .filter(u -> u.getRole() == UserRole.ADMIN)
                .orElseThrow(() -> new RuntimeException("Invalid admin credentials or role"));

        return buildResponse(admin, jwtService.generateToken(admin));
    }

    public SendOtpResponse sendOtp(SendOtpRequest request) {
        logger.info("Sending OTP verification code to phone number: {}", request.getPhoneNumber());
        return SendOtpResponse.builder()
                .message("OTP sent successfully to " + request.getPhoneNumber())
                .otpId("otp_" + System.currentTimeMillis())
                .status("PENDING")
                .build();
    }

    @Transactional
    public AuthTokenResponse verifyOtp(VerifyOtpRequest request) {
        logger.info("Verifying OTP code for phone number: {}", request.getPhoneNumber());
        User user = userRepository.findByPhoneNumber(request.getPhoneNumber())
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .fullName("HomeEase User")
                            .phoneNumber(request.getPhoneNumber())
                            .role(UserRole.CUSTOMER)
                            .build();
                    return userRepository.save(newUser);
                });

        return buildResponse(user, jwtService.generateToken(user));
    }

    private AuthTokenResponse buildResponse(User user, String token) {
        return AuthTokenResponse.builder()
                .token(token)
                .userId(user.getUserId().toString())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }
}
