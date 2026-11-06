package com.homeease.backend.repository;

import com.homeease.backend.model.entity.LedgerAccount;
import com.homeease.backend.model.enums.LedgerAccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {
    Optional<LedgerAccount> findByAccountTypeAndEntityId(LedgerAccountType accountType, UUID entityId);
    Optional<LedgerAccount> findByAccountTypeAndEntityIdIsNull(LedgerAccountType accountType);
}

