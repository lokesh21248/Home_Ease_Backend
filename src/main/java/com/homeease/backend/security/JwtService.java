package com.homeease.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.model.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JwtService {

    private static final Logger logger = LoggerFactory.getLogger(JwtService.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final String secretKey;

    public JwtService(ObjectMapper objectMapper,
                      @Value("${homeease.jwt.secret:404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}") String secretKey) {
        this.objectMapper = objectMapper;
        this.secretKey = secretKey;
    }

    public String generateToken(User user) {
        try {
            // 1. Header
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");
            String encodedHeader = base64UrlEncode(objectMapper.writeValueAsString(header).getBytes(StandardCharsets.UTF_8));

            // 2. Payload
            Instant now = Instant.now();
            Instant exp = now.plus(30, ChronoUnit.DAYS);

            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", user.getUserId().toString());
            claims.put("userId", user.getUserId().toString());
            claims.put("role", user.getRole() != null ? user.getRole().name() : "CUSTOMER");
            claims.put("phoneNumber", user.getPhoneNumber() != null ? user.getPhoneNumber() : "");
            claims.put("fullName", user.getFullName() != null ? user.getFullName() : "");
            claims.put("iat", now.getEpochSecond());
            claims.put("exp", exp.getEpochSecond());

            String encodedPayload = base64UrlEncode(objectMapper.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));

            // 3. Signature
            String signatureInput = encodedHeader + "." + encodedPayload;
            String signature = hmacSha256(signatureInput, secretKey);

            return signatureInput + "." + signature;

        } catch (Exception e) {
            logger.error("Failed to generate JWT token for user {}: {}", user.getUserId(), e.getMessage());
            throw new RuntimeException("JWT generation failure: " + e.getMessage(), e);
        }
    }

    private String hmacSha256(String data, String key) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(hash);
    }

    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
