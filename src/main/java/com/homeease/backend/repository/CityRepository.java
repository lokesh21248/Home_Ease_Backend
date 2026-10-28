package com.homeease.backend.repository;

import com.homeease.backend.model.entity.City;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CityRepository extends JpaRepository<City, UUID> {

    List<City> findByState_StateIdOrderByNameAsc(UUID stateId);

    List<City> findByState_StateIdAndIsActiveTrueOrderByNameAsc(UUID stateId);

    Optional<City> findByState_StateIdAndNameNorm(UUID stateId, String nameNorm);

    long countByState_StateId(UUID stateId);
}
