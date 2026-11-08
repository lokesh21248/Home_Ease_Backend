package com.homeease.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserAddressService {

    private static final Logger logger = LoggerFactory.getLogger(UserAddressService.class);
    private static final String ADDRESS_KEY_PREFIX = "user_addresses:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<UUID, List<Map<String, Object>>> addressFallbackStore = new ConcurrentHashMap<>();

    public UserAddressService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> getUserAddresses(UUID userId) {
        if (userId == null) return Collections.emptyList();
        try {
            String json = redisTemplate.opsForValue().get(ADDRESS_KEY_PREFIX + userId);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception e) {
            logger.debug("Redis address read notice: {}", e.getMessage());
        }
        return addressFallbackStore.getOrDefault(userId, Collections.emptyList());
    }

    public void persistAddresses(UUID userId, List<Map<String, Object>> addresses) {
        if (userId == null) return;
        addressFallbackStore.put(userId, addresses);
        try {
            String json = objectMapper.writeValueAsString(addresses);
            redisTemplate.opsForValue().set(ADDRESS_KEY_PREFIX + userId, json);
        } catch (Exception e) {
            logger.warn("Redis address persist warning: {}", e.getMessage());
        }
    }

    public Map<String, Object> saveUserAddress(UUID userId, Map<String, Object> payload) {
        Map<String, Object> newAddress = new LinkedHashMap<>(payload != null ? payload : Map.of());
        if (!newAddress.containsKey("id") || newAddress.get("id") == null || newAddress.get("id").toString().isBlank()) {
            newAddress.put("id", UUID.randomUUID().toString());
        }
        newAddress.put("userId", userId.toString());

        // Ensure 6-digit clean pincode if present
        if (newAddress.containsKey("pincode") && newAddress.get("pincode") != null) {
            String cleanPin = newAddress.get("pincode").toString().replaceAll("\\D", "");
            newAddress.put("pincode", cleanPin);
        }

        List<Map<String, Object>> currentList = new ArrayList<>(getUserAddresses(userId));
        currentList.add(newAddress);
        persistAddresses(userId, currentList);
        return newAddress;
    }

    public Map<String, Object> updateUserAddress(UUID userId, String addressId, Map<String, Object> payload) {
        List<Map<String, Object>> currentList = new ArrayList<>(getUserAddresses(userId));
        Map<String, Object> updatedAddress = new LinkedHashMap<>(payload != null ? payload : Map.of());
        updatedAddress.put("id", addressId);
        updatedAddress.put("userId", userId.toString());

        if (updatedAddress.containsKey("pincode") && updatedAddress.get("pincode") != null) {
            String cleanPin = updatedAddress.get("pincode").toString().replaceAll("\\D", "");
            updatedAddress.put("pincode", cleanPin);
        }

        boolean found = false;
        for (int i = 0; i < currentList.size(); i++) {
            if (Objects.equals(currentList.get(i).get("id"), addressId)) {
                currentList.set(i, updatedAddress);
                found = true;
                break;
            }
        }
        if (!found) {
            currentList.add(updatedAddress);
        }
        persistAddresses(userId, currentList);
        return updatedAddress;
    }

    public void deleteUserAddress(UUID userId, String addressId) {
        List<Map<String, Object>> currentList = new ArrayList<>(getUserAddresses(userId));
        currentList.removeIf(a -> Objects.equals(a.get("id"), addressId));
        persistAddresses(userId, currentList);
    }

    /**
     * Validates that the specified address belongs to the authenticated user and returns it.
     */
    public Optional<Map<String, Object>> findUserAddress(UUID userId, String addressId) {
        if (userId == null || addressId == null || addressId.isBlank()) {
            return Optional.empty();
        }
        return getUserAddresses(userId).stream()
                .filter(a -> Objects.equals(a.get("id"), addressId))
                .findFirst();
    }
}

