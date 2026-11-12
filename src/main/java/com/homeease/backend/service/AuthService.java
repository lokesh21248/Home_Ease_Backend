package com.homeease.backend.service;

import com.homeease.backend.dto.AuthDto.*;
import com.homeease.backend.exception.InvalidOtpException;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.repository.WorkerRepository;
import com.homeease.backend.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final WorkerRepository workerRepository;
    private final JwtService jwtService;
    private final RedisTemplate<String, String> redisTemplate;
    private final Environment environment;

    @Value("${homeease.admin.password:AdminPassword123!}")
    private String adminPassword;

    public AuthService(UserRepository userRepository,
                       WorkerRepository workerRepository,
                       JwtService jwtService,
                       RedisTemplate<String, String> redisTemplate,
                       Environment environment) {
        this.userRepository = userRepository;
        this.workerRepository = workerRepository;
        this.jwtService = jwtService;
        this.redisTemplate = redisTemplate;
        this.environment = environment;
    }

    /**
     * Normalizes phone number: strips non-digit characters except +,
     * defaults 10-digit Indian numbers to +91.
     */
    public String normalizePhone(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("[^0-9+]", "").trim();
        if (!cleaned.startsWith("+") && cleaned.length() == 10) {
            cleaned = "+91" + cleaned;
        }
        return cleaned;
    }

    private Optional<User> findUserByPhoneFlexible(String phone) {
        String normalized = normalizePhone(phone);
        Optional<User> u = userRepository.findByPhoneNumber(normalized);
        if (u.isPresent()) return u;

        // Try without country code if 10 digits
        if (normalized.startsWith("+91") && normalized.length() > 3) {
            String plain10 = normalized.substring(3);
            Optional<User> alt = userRepository.findByPhoneNumber(plain10);
            if (alt.isPresent()) return alt;
        }
        return Optional.empty();
    }

    /**
     * POST /api/v1/auth/otp/send
     * Generates a 6-digit OTP, saves in Redis (5 min TTL), and returns whether user exists.
     * Rate-limited to 5 OTP requests per phone per 10-minute window to prevent SMS abuse.
     */
    public SendOtpResponse sendOtp(SendOtpRequest request) {
        String phone = normalizePhone(request.getPhoneNumber());
        logger.info("Processing send OTP request for phone: {}", phone);

        // --- Rate limiting: max 5 OTP sends per phone per 10 minutes ---
        String rateLimitKey = "auth:otp:ratelimit:" + phone;
        try {
            Long attempts = redisTemplate.opsForValue().increment(rateLimitKey);
            if (attempts != null && attempts == 1) {
                redisTemplate.expire(rateLimitKey, Duration.ofMinutes(10));
            }
            if (attempts != null && attempts > 5) {
                logger.warn("OTP rate limit exceeded for phone: {}", phone);
                throw new RuntimeException("Too many OTP requests. Please wait 10 minutes before requesting again.");
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            logger.warn("Redis unavailable for OTP rate limiting: {}", e.getMessage());
        }

        boolean userExists = findUserByPhoneFlexible(phone).isPresent();
        boolean isNewUser = !userExists;

        // Generate cryptographically secure 6-digit OTP code
        String otpCode = String.format("%06d", new java.security.SecureRandom().nextInt(900000) + 100000);

        // Store OTP in Redis with 5 minutes TTL (overwrites previous unused OTP)
        try {
            redisTemplate.opsForValue().set("auth:otp:" + phone, otpCode, Duration.ofMinutes(5));
            logger.info("Saved OTP for {} to Redis (TTL 5 mins).", phone);
        } catch (Exception e) {
            logger.warn("Redis unavailable for OTP storage: {}", e.getMessage());
        }

        return SendOtpResponse.builder()
                .status("SUCCESS")
                .message("OTP sent successfully.")
                .otpId("otp_" + System.currentTimeMillis())
                .data(SendOtpData.builder()
                        .isNewUser(isNewUser)
                        .userExists(userExists)
                        .build())
                .build();
    }

    /**
     * POST /api/v1/auth/otp/verify
     * Validates 6-digit OTP. Returns JWT token, onboarding & registration flags.
     */
    @Transactional
    public AuthTokenResponse verifyOtp(VerifyOtpRequest request) {
        String phone = normalizePhone(request.getPhoneNumber());
        String code = request.getOtpCode() != null ? request.getOtpCode().trim() : "";
        logger.info("Verifying OTP for phone: {}", phone);

        // 1. Verify OTP against Redis. Master testing code (123456) is strictly restricted to dev/test profiles.
        String cachedOtp = null;
        try {
            cachedOtp = redisTemplate.opsForValue().get("auth:otp:" + phone);
        } catch (Exception e) {
            logger.warn("Redis error while retrieving OTP: {}", e.getMessage());
        }

        boolean isDevOrTest = environment != null && environment.acceptsProfiles(Profiles.of("dev", "test", "local"));
        boolean isValid = (cachedOtp != null && cachedOtp.equals(code)) || (isDevOrTest && "123456".equals(code));

        if (!isValid) {
            throw new InvalidOtpException("INVALID_OTP: The OTP code is invalid or has expired.");
        }

        // Clean up verified OTP
        try {
            redisTemplate.delete("auth:otp:" + phone);
        } catch (Exception ignored) {}

        // 2. Query DB to determine if worker already exists
        Optional<User> existingUserOpt = findUserByPhoneFlexible(phone);

        if (existingUserOpt.isEmpty()) {
            // NEW USER (Not in DB):
            // Create user record with default CUSTOMER role so they have a valid persistent userId and cryptographic JWT token
            User newUser = User.builder()
                    .phoneNumber(phone)
                    .fullName("") // Will be filled in registration step
                    .role(UserRole.CUSTOMER)
                    .build();
            User saved = userRepository.save(newUser);
            String token = jwtService.generateToken(saved);

            return AuthTokenResponse.builder()
                    .token(token)
                    .userId(saved.getUserId().toString())
                    .phoneNumber(phone)
                    .role(UserRole.CUSTOMER)
                    .isNewUser(true)
                    .userExists(false)
                    .isRegistered(false)
                    .isKycCompleted(false)
                    .isVerified(false)
                    .build();
        }

        // EXISTING USER (Already in DB):
        User user = existingUserOpt.get();
        String token = jwtService.generateToken(user);

        boolean isRegistered = user.getFullName() != null && !user.getFullName().trim().isEmpty();
        boolean isKycCompleted = false;
        boolean isVerified = false;

        Optional<Worker> workerOpt = workerRepository.findByUser_UserId(user.getUserId());
        if (workerOpt.isPresent()) {
            Worker worker = workerOpt.get();
            isVerified = Boolean.TRUE.equals(worker.getIsVerified());
            String status = worker.getKycStatus() != null ? worker.getKycStatus().toUpperCase() : "NOT_UPLOADED";
            isKycCompleted = "APPROVED".equals(status) || "PENDING".equals(status) ||
                    (worker.getPanDocUrl() != null && !worker.getPanDocUrl().isBlank() &&
                     worker.getAadhaarDocUrl() != null && !worker.getAadhaarDocUrl().isBlank());
        }

        return AuthTokenResponse.builder()
                .token(token)
                .userId(user.getUserId().toString())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .email(user.getEmail())
                .role(user.getRole())
                .isNewUser(false)
                .userExists(true)
                .isRegistered(isRegistered)
                .isKycCompleted(isKycCompleted)
                .isVerified(isVerified)
                .build();
    }

    /**
     * POST /api/v1/users/register
     * Registers worker or customer account info (full name, email, role).
     *
     * <p>Security: Only WORKER or CUSTOMER roles are accepted from the client.
     * The ADMIN role can never be self-assigned through this endpoint.
     * User identity is resolved strictly from the verified JWT in the Authorization header.
     */
    @Transactional
    public User registerUser(RegisterUserRequest request, String authHeader) {
        String phone = normalizePhone(request.getPhoneNumber());

        // --- Security: prevent ADMIN role self-assignment ---
        UserRole requestedRole = request.getRole() != null ? request.getRole() : UserRole.WORKER;
        UserRole targetRole;
        if (requestedRole == UserRole.ADMIN) {
            logger.warn("SECURITY: Attempt to self-assign ADMIN role rejected for phone {}", phone);
            targetRole = UserRole.CUSTOMER;
        } else {
            targetRole = requestedRole;
        }

        User user = null;

        // 1. Resolve identity strictly from JWT (most trusted source)
        if (authHeader != null && !authHeader.isBlank()) {
            UUID extracted = jwtService.extractUserId(authHeader);
            if (extracted != null) {
                user = userRepository.findById(extracted).orElse(null);
            }
        }

        // 2. Fallback to phone number lookup (new user registration flow)
        if (user == null && phone != null && !phone.isBlank()) {
            user = findUserByPhoneFlexible(phone).orElse(null);
        }

        if (user == null) {
            user = User.builder()
                    .phoneNumber(phone)
                    .fullName(request.getFullName().trim())
                    .email(request.getEmail() != null ? request.getEmail().trim() : null)
                    .role(targetRole)
                    .build();
        } else {
            user.setFullName(request.getFullName().trim());
            if (request.getEmail() != null && !request.getEmail().isBlank()) {
                user.setEmail(request.getEmail().trim());
            }
            // Prevent ADMIN role demotion — only allow CUSTOMER <-> WORKER changes
            if (user.getRole() != UserRole.ADMIN) {
                user.setRole(targetRole);
            }
            if (phone != null && !phone.isBlank() && (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank())) {
                user.setPhoneNumber(phone);
            }
        }

        user = userRepository.save(user);

        // If registered as WORKER, initialize base Worker profile if missing
        if (user.getRole() == UserRole.WORKER) {
            final User finalUser = user;
            workerRepository.findByUser_UserId(user.getUserId()).orElseGet(() -> {
                Worker w = Worker.builder()
                        .user(finalUser)
                        .isVerified(false)
                        .isOnline(false)
                        .kycStatus("NOT_UPLOADED")
                        .build();
                return workerRepository.save(w);
            });
        }

        return user;
    }

    /**
     * Firebase token authentication — STUB.
     * This method returns a fixed test user and must NEVER be called in production.
     * It is kept for Firebase SDK integration reference only.
     * The endpoint must be disabled (not registered) in production profiles.
     * @deprecated Use OTP-based login instead.
     */
    @Transactional
    @Deprecated
    public AuthTokenResponse authenticateFirebaseToken(FirebaseLoginRequest request) {
        boolean isDevOrTest = environment != null && environment.acceptsProfiles(Profiles.of("dev", "test", "local"));
        if (!isDevOrTest) {
            throw new SecurityException("Firebase stub authentication is not available in production. Use OTP login.");
        }
        // Stub only used in dev/test for Firebase SDK integration testing
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

    public AuthTokenResponse authenticateAdmin(AdminLoginRequest request) {
        if (request.getPassword() == null || !request.getPassword().equals(adminPassword)) {
            throw new SecurityException("Invalid admin credentials");
        }

        User admin = userRepository.findByEmail(request.getEmail())
                .filter(u -> u.getRole() == UserRole.ADMIN)
                .orElseThrow(() -> new SecurityException("Invalid admin credentials or role"));

        return buildResponse(admin, jwtService.generateToken(admin));
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
