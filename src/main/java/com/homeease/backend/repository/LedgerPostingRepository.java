package com.homeease.backend.repository;

import com.homeease.backend.model.entity.LedgerPosting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerPostingRepository extends JpaRepository<LedgerPosting, UUID> {
    List<LedgerPosting> findByJournalEntry_EntryId(UUID entryId);
}

