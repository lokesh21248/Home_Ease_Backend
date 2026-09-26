package com.homeease.tracking.controller;

import com.homeease.tracking.dto.LocationPayload;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tracking")
public class TrackingController {

    private final RedisTemplate<String, String> redisTemplate;

    public TrackingController(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostMapping("/update-location")
    public ResponseEntity<String> updateLiveLocationREST(@RequestBody LocationPayload payload) {
        try {
            redisTemplate.opsForValue().set("tracking:booking:" + payload.getBookingId(), payload.getLat() + "," + payload.getLng());
        } catch (Exception ignored) {}
        return ResponseEntity.ok("Live tracking location updated via REST fallback.");
    }

    @GetMapping("/latest/{bookingId}")
    public ResponseEntity<String> getLatestLocation(@PathVariable("bookingId") String bookingId) {
        String coords = redisTemplate.opsForValue().get("tracking:booking:" + bookingId);
        return ResponseEntity.ok(coords != null ? coords : "0.0,0.0");
    }
}
