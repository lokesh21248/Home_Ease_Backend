package com.homeease.backend.repository;

import com.homeease.backend.model.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {
    List<Review> findByWorker_WorkerId(UUID workerId);
    List<Review> findByUser_UserId(UUID userId);
}
