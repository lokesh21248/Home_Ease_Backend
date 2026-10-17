package com.homeease.tracking.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtTokenValidator {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenValidator.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final String secretKey;

    public JwtTokenValidator(
            ObjectMapper objectMapper,
            @Value("${homeease.jwt.secret:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}") String secretKey) {
        this.objectMapper = objectMapper;
        this.secretKey = secretKey;
    }

    /**
     * Validates JWT token and extracts claims. Returns null if invalid or expired.
     */
    public Map<String, Object> validateAndExtractClaims(String authHeaderOrToken) {
        if (authHeaderOrToken == null || authHeaderOrToken.isBlank()) {
            return null;
        }

        String token = authHeaderOrToken.startsWith("Bearer ")
                ? authHeaderOrToken.substring(7).trim()
                : authHeaderOrToken.trim();

        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }

            // Verify signature
            String content = parts[0] + "." + parts[1];
            String expectedSignature = hmacSha256(content, secretKey);
            if (!expectedSignature.equals(parts[2])) {
                logger.warn("Invalid JWT signature received in tracking service");
                return null;
            }

            // Decode payload claims
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            Map<String, Object> claims = objectMapper.readValue(
                    decoded,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
            );

            // Check expiration
            if (claims.containsKey("exp")) {
                long exp = ((Number) claims.get("exp")).longValue();
                if (Instant.now().getEpochSecond() > exp) {
                    logger.warn("Expired JWT token received in tracking service (exp: {})", exp);
                    return null;
                }
            }

            return claims;
        } catch (Exception e) {
            logger.warn("Failed to parse and validate token in tracking service: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts authenticated User / Worker UUID from token.
     */
    public UUID extractUserId(String authHeaderOrToken) {
        Map<String, Object> claims = validateAndExtractClaims(authHeaderOrToken);
        if (claims == null) return null;

        Object userIdObj = claims.get("userId") != null ? claims.get("userId") : claims.get("sub");
        if (userIdObj != null) {
            try {
                return UUID.fromString(userIdObj.toString());
            } catch (Exception e) {
                logger.warn("Malformed UUID in token claims: {}", userIdObj);
            }
        }
        return null;
    }

    /**
     * Extracts Role (e.g. WORKER, CUSTOMER, ADMIN).
     */
    public String extractRole(String authHeaderOrToken) {
        Map<String, Object> claims = validateAndExtractClaims(authHeaderOrToken);
        if (claims == null) return null;
        return (String) claims.get("role");
    }

    private String hmacSha256(String data, String key) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}
