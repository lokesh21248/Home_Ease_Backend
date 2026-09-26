package com.homeease.backend.repository;

import com.homeease.backend.model.entity.Banner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BannerRepository extends JpaRepository<Banner, UUID> {
    List<Banner> findByIsActiveTrue();
    List<Banner> findByIsActive(Boolean isActive);
}
