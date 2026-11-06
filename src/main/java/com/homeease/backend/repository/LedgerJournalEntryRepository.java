package com.homeease.backend.repository;

import com.homeease.backend.model.entity.LedgerJournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerJournalEntryRepository extends JpaRepository<LedgerJournalEntry, UUID> {
    Optional<LedgerJournalEntry> findByIdempotencyKey(String idempotencyKey);
    List<LedgerJournalEntry> findByBooking_BookingIdOrderByCreatedAtDesc(UUID bookingId);
}
