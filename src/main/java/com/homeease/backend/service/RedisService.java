package com.homeease.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Minimal Redis access wrapper used for OTP caching, rate limiting and transient
 * delivery artefacts. All failures here are best-effort and must never break the
 * booking lifecycle.
 */
@Service
@ConditionalOnMissingBean(RedisService.class)
public class RedisService {

    private static final Logger logger = LoggerFactory.getLogger(RedisService.class);
    private final StringRedisTemplate redisTemplate;

    public RedisService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void set(String key, String value, java.time.Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            logger.debug("Redis SET failed for key {}: {}", key, e.getMessage());
        }
    }

    public String get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            logger.debug("Redis GET failed for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            logger.debug("Redis DELETE failed for key {}: {}", key, e.getMessage());
        }
    }

    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            logger.debug("Redis EXISTS failed for key {}: {}", key, e.getMessage());
            return false;
        }
    }
}
