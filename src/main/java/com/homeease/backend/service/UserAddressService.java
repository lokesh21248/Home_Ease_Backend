package com.homeease.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class UserAddressService {

    private static final Logger logger = LoggerFactory.getLogger(UserAddressService.class);
    private static final String ADDRESS_KEY_PREFIX = "user_addresses:";
    private static final Pattern PINCODE_PATTERN = Pattern.compile("\\b(\\d{6})\\b");

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<UUID, List<Map<String, Object>>> addressFallbackStore = new ConcurrentHashMap<>();

    public UserAddressService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> getUserAddresses(UUID userId) {
        if (userId == null) return Collections.emptyList();
        List<Map<String, Object>> addresses = null;
        try {
            String json = redisTemplate.opsForValue().get(ADDRESS_KEY_PREFIX + userId);
            if (json != null && !json.isBlank()) {
                addresses = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception e) {
            logger.debug("Redis address read notice: {}", e.getMessage());
        }

        if (addresses == null) {
            addresses = addressFallbackStore.getOrDefault(userId, Collections.emptyList());
        }

        if (addresses.isEmpty()) {
            return Collections.emptyList();
        }

        // Sort so that isDefault = true is always first (index 0)
        List<Map<String, Object>> sorted = new ArrayList<>(addresses);
        sorted.sort((a, b) -> {
            boolean aDef = Boolean.TRUE.equals(a.get("isDefault"));
            boolean bDef = Boolean.TRUE.equals(b.get("isDefault"));
            return Boolean.compare(bDef, aDef);
        });

        // Ensure at least one address is marked default if list is not empty
        boolean hasDefault = sorted.stream().anyMatch(a -> Boolean.TRUE.equals(a.get("isDefault")));
        if (!hasDefault && !sorted.isEmpty()) {
            sorted.get(0).put("isDefault", true);
            persistAddresses(userId, sorted);
        }

        return sorted;
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

        // Extract clean 6-digit pincode
        String pincode = extractPincode(newAddress);
        if (pincode != null) {
            newAddress.put("pincode", pincode);
        }

        List<Map<String, Object>> currentList = new ArrayList<>(getUserAddresses(userId));
        boolean makeDefault = Boolean.TRUE.equals(newAddress.get("isDefault")) || currentList.isEmpty();

        if (makeDefault) {
            for (Map<String, Object> addr : currentList) {
                addr.put("isDefault", false);
            }
            newAddress.put("isDefault", true);
            currentList.add(0, newAddress);
        } else {
            newAddress.put("isDefault", false);
            currentList.add(newAddress);
        }

        persistAddresses(userId, currentList);
        return newAddress;
    }

    public Map<String, Object> updateUserAddress(UUID userId, String addressId, Map<String, Object> payload) {
        List<Map<String, Object>> currentList = new ArrayList<>(getUserAddresses(userId));
        Map<String, Object> updatedAddress = new LinkedHashMap<>(payload != null ? payload : Map.of());
        updatedAddress.put("id", addressId);
        updatedAddress.put("userId", userId.toString());

        String pincode = extractPincode(updatedAddress);
        if (pincode != null) {
            updatedAddress.put("pincode", pincode);
        }

        boolean makeDefault = Boolean.TRUE.equals(updatedAddress.get("isDefault"));
        if (makeDefault) {
            for (Map<String, Object> addr : currentList) {
                addr.put("isDefault", false);
            }
            updatedAddress.put("isDefault", true);
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
            if (makeDefault) {
                currentList.add(0, updatedAddress);
            } else {
                currentList.add(updatedAddress);
            }
        }

        persistAddresses(userId, currentList);
        return updatedAddress;
    }

    public void deleteUserAddress(UUID userId, String addressId) {
        List<Map<String, Object>> currentList = new ArrayList<>(getUserAddresses(userId));
        currentList.removeIf(a -> Objects.equals(a.get("id"), addressId));
        if (!currentList.isEmpty() && currentList.stream().noneMatch(a -> Boolean.TRUE.equals(a.get("isDefault")))) {
            currentList.get(0).put("isDefault", true);
        }
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

    private String extractPincode(Map<String, Object> addr) {
        if (addr.containsKey("pincode") && addr.get("pincode") != null) {
            String clean = addr.get("pincode").toString().replaceAll("\\D", "");
            if (clean.length() == 6) return clean;
        }
        // Fallback: check city or addressLine for a 6-digit sequence
        String combined = (addr.getOrDefault("city", "") + " " + addr.getOrDefault("addressLine", "")).trim();
        Matcher matcher = PINCODE_PATTERN.matcher(combined);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
