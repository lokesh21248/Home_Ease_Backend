package com.homeease.tracking.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Builds HS256 JWTs exactly as the core's JwtService does, for tests. */
public final class TestJwt {

    public static final String SECRET = "tracking-test-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private TestJwt() {
    }

    public static String bearer(String userId, String role) {
        return "Bearer " + token(userId, role, Instant.now().getEpochSecond() + 3600, SECRET);
    }

    public static String token(String userId, String role, long expEpochSeconds, String secret) {
        try {
            String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String payload = b64("{\"sub\":\"" + userId + "\",\"userId\":\"" + userId + "\",\"role\":\"" + role
                    + "\",\"exp\":" + expEpochSeconds + "}");
            String input = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
            return input + "." + sig;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
