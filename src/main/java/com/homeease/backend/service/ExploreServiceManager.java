package com.homeease.backend.service;

import com.homeease.backend.dto.ExploreServiceDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.ExploreService;
import com.homeease.backend.model.entity.ServiceEntity;
import com.homeease.backend.repository.ExploreServiceRepository;
import com.homeease.backend.repository.ServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class ExploreServiceManager {

    private static final Logger log = LoggerFactory.getLogger(ExploreServiceManager.class);

    private final ExploreServiceRepository exploreServiceRepository;
    private final ServiceRepository serviceRepository;

    public ExploreServiceManager(
            ExploreServiceRepository exploreServiceRepository,
            ServiceRepository serviceRepository) {
        this.exploreServiceRepository = exploreServiceRepository;
        this.serviceRepository = serviceRepository;
    }

    /**
     * Admin: Retrieve all explore services (active and inactive, excluding
     * soft-deleted)
     */
    @Transactional(readOnly = true)
    public List<ExploreServiceResponse> getAllForAdmin() {
        return exploreServiceRepository.findByDeletedAtIsNullOrderByDisplayOrderAscCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Customer: Retrieve active explore services sorted by display_order ASC
     * Optionally filtered by categoryId
     */
    @Transactional(readOnly = true)
    public List<ExploreServiceResponse> getActiveForCustomer(UUID categoryId) {
        List<ExploreService> list;
        if (categoryId != null) {
            list = exploreServiceRepository
                    .findByCategory_ServiceIdAndIsActiveTrueAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtDesc(
                            categoryId);
        } else {
            list = exploreServiceRepository.findByIsActiveTrueAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtDesc();
        }
        return list.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get explore service by ID
     */
    @Transactional(readOnly = true)
    public ExploreServiceResponse getById(UUID id) {
        ExploreService entity = exploreServiceRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("ExploreService not found with id: " + id));
        return toResponse(entity);
    }

    /**
     * Create new Explore Service
     */
    public ExploreServiceResponse create(ExploreServiceRequest request) {
        String slug = generateUniqueSlug(request.getName(), request.getSlug(), null);

        ServiceEntity category = null;
        if (request.getCategoryId() != null) {
            category = serviceRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Category not found with id: " + request.getCategoryId()));
        }

        ExploreService entity = ExploreService.builder()
                .name(request.getName().trim())
                .slug(slug)
                .description(request.getDescription())
                .category(category)
                .imageUrl(request.getImageUrl())
                .price(request.getPrice())
                .originalPrice(request.getOriginalPrice())
                .rating(request.getRating() != null ? request.getRating() : new BigDecimal("4.8"))
                .reviewCount(request.getReviewCount() != null ? request.getReviewCount() : 0)
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0)
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .isFeatured(request.getIsFeatured() != null ? request.getIsFeatured() : false)
                .build();

        ExploreService saved = exploreServiceRepository.save(entity);
        log.info("Created explore service: id={}, name='{}', slug='{}'", saved.getId(), saved.getName(),
                saved.getSlug());
        return toResponse(saved);
    }

    /**
     * Update existing Explore Service
     */
    public ExploreServiceResponse update(UUID id, ExploreServiceRequest request) {
        ExploreService entity = exploreServiceRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("ExploreService not found with id: " + id));

        String slug = generateUniqueSlug(request.getName(), request.getSlug(), id);

        ServiceEntity category = null;
        if (request.getCategoryId() != null) {
            category = serviceRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Category not found with id: " + request.getCategoryId()));
        }

        entity.setName(request.getName().trim());
        entity.setSlug(slug);
        entity.setDescription(request.getDescription());
        entity.setCategory(category);
        entity.setImageUrl(request.getImageUrl());
        entity.setPrice(request.getPrice());
        entity.setOriginalPrice(request.getOriginalPrice());
        if (request.getRating() != null) {
            entity.setRating(request.getRating());
        }
        if (request.getReviewCount() != null) {
            entity.setReviewCount(request.getReviewCount());
        }
        if (request.getDisplayOrder() != null) {
            entity.setDisplayOrder(request.getDisplayOrder());
        }
        if (request.getIsActive() != null) {
            entity.setIsActive(request.getIsActive());
        }
        if (request.getIsFeatured() != null) {
            entity.setIsFeatured(request.getIsFeatured());
        }

        ExploreService saved = exploreServiceRepository.save(entity);
        log.info("Updated explore service: id={}, name='{}'", saved.getId(), saved.getName());
        return toResponse(saved);
    }

    /**
     * Toggle Active Status
     */
    public ExploreServiceResponse toggleActive(UUID id, boolean isActive) {
        ExploreService entity = exploreServiceRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("ExploreService not found with id: " + id));
        entity.setIsActive(isActive);
        ExploreService saved = exploreServiceRepository.save(entity);
        log.info("Toggled active status for explore service id={}: isActive={}", id, isActive);
        return toResponse(saved);
    }

    /**
     * Reorder Explore Services in batch
     */
    public void reorder(List<ReorderExploreServicesRequest.OrderItem> items) {
        if (items == null || items.isEmpty())
            return;

        for (ReorderExploreServicesRequest.OrderItem item : items) {
            exploreServiceRepository.findByIdAndDeletedAtIsNull(item.getId()).ifPresent(service -> {
                service.setDisplayOrder(item.getDisplayOrder());
                exploreServiceRepository.save(service);
            });
        }
        log.info("Reordered {} explore services", items.size());
    }

    /**
     * Delete explore service (soft delete by default, hard delete optional)
     */
    public void delete(UUID id, boolean hardDelete) {
        ExploreService entity = exploreServiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ExploreService not found with id: " + id));

        if (hardDelete) {
            exploreServiceRepository.delete(entity);
            log.info("Hard deleted explore service id={}", id);
        } else {
            entity.setIsActive(false);
            entity.setDeletedAt(Instant.now());
            exploreServiceRepository.save(entity);
            log.info("Soft deleted explore service id={}", id);
        }
    }

    // --- Helper: Generate unique slug ---
    private String generateUniqueSlug(String name, String proposedSlug, UUID existingId) {
        String base = (proposedSlug != null && !proposedSlug.trim().isEmpty())
                ? proposedSlug.trim()
                : name;

        String normalized = base.toLowerCase(Locale.ENGLISH)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");

        if (normalized.isEmpty()) {
            normalized = "service";
        }

        String candidate = normalized;
        int counter = 1;

        while (isSlugTaken(candidate, existingId)) {
            candidate = normalized + "-" + counter;
            counter++;
        }

        return candidate;
    }

    private boolean isSlugTaken(String slug, UUID existingId) {
        if (existingId == null) {
            return exploreServiceRepository.existsBySlugAndDeletedAtIsNull(slug);
        } else {
            return exploreServiceRepository.existsBySlugAndIdNot(slug, existingId);
        }
    }

    // --- Helper: Entity to Response DTO ---
    private ExploreServiceResponse toResponse(ExploreService entity) {
        return ExploreServiceResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .slug(entity.getSlug())
                .description(entity.getDescription())
                .categoryId(entity.getCategory() != null ? entity.getCategory().getServiceId() : null)
                .categoryName(entity.getCategory() != null ? entity.getCategory().getName() : null)
                .imageUrl(entity.getImageUrl())
                .price(entity.getPrice())
                .originalPrice(entity.getOriginalPrice())
                .rating(entity.getRating())
                .reviewCount(entity.getReviewCount())
                .displayOrder(entity.getDisplayOrder())
                .isActive(entity.getIsActive())
                .isFeatured(entity.getIsFeatured())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
