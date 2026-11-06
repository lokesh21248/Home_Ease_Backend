package com.homeease.backend.repository;

import com.homeease.backend.model.entity.ExploreService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExploreServiceRepository extends JpaRepository<ExploreService, UUID> {

    // Admin view: all non-deleted services
    List<ExploreService> findByDeletedAtIsNullOrderByDisplayOrderAscCreatedAtDesc();

    // Customer view: active & non-deleted services
    List<ExploreService> findByIsActiveTrueAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtDesc();

    // Category filter for customer view
    List<ExploreService> findByCategory_ServiceIdAndIsActiveTrueAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtDesc(
            UUID categoryId);

    // Find non-deleted by ID
    Optional<ExploreService> findByIdAndDeletedAtIsNull(UUID id);

    // Find non-deleted by slug
    Optional<ExploreService> findBySlugAndIsActiveTrueAndDeletedAtIsNull(String slug);

    // Slug collision checks
    boolean existsBySlugAndDeletedAtIsNull(String slug);

    @Query("SELECT COUNT(e) > 0 FROM ExploreService e WHERE e.slug = :slug AND e.id <> :id AND e.deletedAt IS NULL")
    boolean existsBySlugAndIdNot(@Param("slug") String slug, @Param("id") UUID id);
}
