package com.homeease.backend.repository;

import com.homeease.backend.model.entity.State;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StateRepository extends JpaRepository<State, UUID> {

    Optional<State> findByNameNorm(String nameNorm);

    List<State> findAllByOrderByNameAsc();

    List<State> findByIsActiveTrueOrderByNameAsc();
}
