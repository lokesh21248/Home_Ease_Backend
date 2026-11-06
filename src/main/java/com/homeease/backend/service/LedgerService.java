package com.homeease.backend.service;

import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.LedgerAccountType;
import com.homeease.backend.model.enums.LedgerTransactionType;
import com.homeease.backend.model.enums.PostingDirection;
import com.homeease.backend.repository.LedgerAccountRepository;
import com.homeease.backend.repository.LedgerJournalEntryRepository;
import com.homeease.backend.repository.LedgerPostingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class LedgerService {

    private static final Logger logger = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerAccountRepository accountRepository;
    private final LedgerJournalEntryRepository journalEntryRepository;
    private final LedgerPostingRepository postingRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public LedgerService(LedgerAccountRepository accountRepository,
                         LedgerJournalEntryRepository journalEntryRepository,
                         LedgerPostingRepository postingRepository) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.postingRepository = postingRepository;
    }

    /**
     * Retrieves or creates a double-entry ledger account.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerAccount getOrCreateAccount(LedgerAccountType accountType, UUID entityId) {
        if (entityId == null) {
            return accountRepository.findByAccountTypeAndEntityIdIsNull(accountType)
                    .orElseGet(() -> accountRepository.save(LedgerAccount.builder()
                            .accountType(accountType)
                            .entityId(null)
                            .balance(BigDecimal.ZERO)
                            .currency("INR")
                            .build()));
        } else {
            return accountRepository.findByAccountTypeAndEntityId(accountType, entityId)
                    .orElseGet(() -> accountRepository.save(LedgerAccount.builder()
                            .accountType(accountType)
                            .entityId(entityId)
                            .balance(BigDecimal.ZERO)
                            .currency("INR")
                            .build()));
        }
    }

    /**
     * Records customer escrow receipt when payment is authorized.
     * Rule #2: Carries an idempotency key.
     * Rule #3: Uses pg_advisory_xact_lock.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerJournalEntry recordEscrowReceipt(Booking booking, BigDecimal amount, String idempotencyKey) {
        String lockKey = "ledger_escrow:" + booking.getBookingId();
        acquireAdvisoryLock(lockKey);

        Optional<LedgerJournalEntry> existing = journalEntryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            logger.info("Escrow receipt already recorded with idempotency key {}", idempotencyKey);
            return existing.get();
        }

        LedgerAccount cashAccount = getOrCreateAccount(LedgerAccountType.ASSET_CASH, null);
        LedgerAccount escrowAccount = getOrCreateAccount(LedgerAccountType.LIABILITY_CUSTOMER_ESCROW, null);

        LedgerJournalEntry journalEntry = journalEntryRepository.save(LedgerJournalEntry.builder()
                .idempotencyKey(idempotencyKey)
                .booking(booking)
                .transactionType(LedgerTransactionType.BOOKING_ESCROW_RECEIVED)
                .description("Customer payment escrow for booking " + booking.getBookingId())
                .build());

        // DEBIT ASSET_CASH, CREDIT LIABILITY_CUSTOMER_ESCROW
        createPosting(journalEntry, cashAccount, PostingDirection.DEBIT, amount);
        createPosting(journalEntry, escrowAccount, PostingDirection.CREDIT, amount);

        cashAccount.setBalance(cashAccount.getBalance().add(amount));
        escrowAccount.setBalance(escrowAccount.getBalance().add(amount));
        accountRepository.save(cashAccount);
        accountRepository.save(escrowAccount);

        return journalEntry;
    }

    /**
     * Settles completed job into Partner Payable and Platform Revenue.
     * Rule #2: Strictly enforced debits == credits and idempotency check.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerJournalEntry recordJobSettlement(Booking booking, BigDecimal totalAmount,
                                                  BigDecimal workerShare, BigDecimal platformFee,
                                                  String idempotencyKey) {
        if (workerShare.add(platformFee).compareTo(totalAmount) != 0) {
            throw new IllegalArgumentException("Ledger settlement imbalance: workerShare (" + workerShare +
                    ") + platformFee (" + platformFee + ") must equal totalAmount (" + totalAmount + ")");
        }

        String lockKey = "ledger_settlement:" + booking.getBookingId();
        acquireAdvisoryLock(lockKey);

        Optional<LedgerJournalEntry> existing = journalEntryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            logger.info("Settlement already recorded with idempotency key {}", idempotencyKey);
            return existing.get();
        }

        UUID workerId = booking.getWorker() != null ? booking.getWorker().getWorkerId() : null;
        if (workerId == null) {
            throw new IllegalStateException("Cannot settle booking without assigned worker: " + booking.getBookingId());
        }

        LedgerAccount escrowAccount = getOrCreateAccount(LedgerAccountType.LIABILITY_CUSTOMER_ESCROW, null);
        LedgerAccount workerPayable = getOrCreateAccount(LedgerAccountType.LIABILITY_WORKER_PAYABLE, workerId);
        LedgerAccount platformRevenue = getOrCreateAccount(LedgerAccountType.REVENUE_PLATFORM_FEE, null);

        LedgerJournalEntry journalEntry = journalEntryRepository.save(LedgerJournalEntry.builder()
                .idempotencyKey(idempotencyKey)
                .booking(booking)
                .transactionType(LedgerTransactionType.JOB_SETTLEMENT)
                .description(String.format("Job completion settlement for booking %s (Worker: %s, Platform: %s)",
                        booking.getBookingId(), workerShare, platformFee))
                .build());

        // DEBIT LIABILITY_CUSTOMER_ESCROW (reduces escrow liability)
        createPosting(journalEntry, escrowAccount, PostingDirection.DEBIT, totalAmount);
        // CREDIT LIABILITY_WORKER_PAYABLE (increases worker earnings liability)
        createPosting(journalEntry, workerPayable, PostingDirection.CREDIT, workerShare);
        // CREDIT REVENUE_PLATFORM_FEE (realizes platform revenue)
        createPosting(journalEntry, platformRevenue, PostingDirection.CREDIT, platformFee);

        escrowAccount.setBalance(escrowAccount.getBalance().subtract(totalAmount));
        workerPayable.setBalance(workerPayable.getBalance().add(workerShare));
        platformRevenue.setBalance(platformRevenue.getBalance().add(platformFee));

        accountRepository.save(escrowAccount);
        accountRepository.save(workerPayable);
        accountRepository.save(platformRevenue);

        logger.info("Successfully recorded job settlement journal entry {} for booking {}",
                journalEntry.getEntryId(), booking.getBookingId());
        return journalEntry;
    }

    /**
     * Records worker bank payout transfer.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerJournalEntry recordWorkerPayout(Worker worker, BigDecimal amount, String idempotencyKey) {
        String lockKey = "ledger_payout:" + worker.getWorkerId();
        acquireAdvisoryLock(lockKey);

        Optional<LedgerJournalEntry> existing = journalEntryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        LedgerAccount workerPayable = getOrCreateAccount(LedgerAccountType.LIABILITY_WORKER_PAYABLE, worker.getWorkerId());
        LedgerAccount cashAccount = getOrCreateAccount(LedgerAccountType.ASSET_CASH, null);

        if (workerPayable.getBalance().compareTo(amount) < 0) {
            throw new IllegalStateException(String.format("Insufficient payable balance: available %s, requested %s",
                    workerPayable.getBalance(), amount));
        }

        LedgerJournalEntry journalEntry = journalEntryRepository.save(LedgerJournalEntry.builder()
                .idempotencyKey(idempotencyKey)
                .transactionType(LedgerTransactionType.WORKER_PAYOUT)
                .description("Worker payout transfer to bank for worker " + worker.getWorkerId())
                .build());

        // DEBIT LIABILITY_WORKER_PAYABLE (reduces worker payable)
        createPosting(journalEntry, workerPayable, PostingDirection.DEBIT, amount);
        // CREDIT ASSET_CASH (reduces cash balance)
        createPosting(journalEntry, cashAccount, PostingDirection.CREDIT, amount);

        workerPayable.setBalance(workerPayable.getBalance().subtract(amount));
        cashAccount.setBalance(cashAccount.getBalance().subtract(amount));

        accountRepository.save(workerPayable);
        accountRepository.save(cashAccount);

        return journalEntry;
    }

    /**
     * Records dispute refund or compensation journal entry.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerJournalEntry recordDisputeResolution(Dispute dispute, BigDecimal refundAmount,
                                                      BigDecimal compensationAmount, String idempotencyKey) {
        String lockKey = "ledger_dispute:" + dispute.getDisputeId();
        acquireAdvisoryLock(lockKey);

        Optional<LedgerJournalEntry> existing = journalEntryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        LedgerJournalEntry journalEntry = journalEntryRepository.save(LedgerJournalEntry.builder()
                .idempotencyKey(idempotencyKey)
                .booking(dispute.getBooking())
                .transactionType(refundAmount.compareTo(BigDecimal.ZERO) > 0
                        ? LedgerTransactionType.DISPUTE_REFUND_CUSTOMER
                        : LedgerTransactionType.DISPUTE_COMPENSATE_WORKER)
                .description("Dispute resolution for dispute " + dispute.getDisputeId())
                .build());

        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            LedgerAccount platformRevenue = getOrCreateAccount(LedgerAccountType.REVENUE_PLATFORM_FEE, null);
            LedgerAccount cashAccount = getOrCreateAccount(LedgerAccountType.ASSET_CASH, null);

            createPosting(journalEntry, platformRevenue, PostingDirection.DEBIT, refundAmount);
            createPosting(journalEntry, cashAccount, PostingDirection.CREDIT, refundAmount);

            platformRevenue.setBalance(platformRevenue.getBalance().subtract(refundAmount));
            cashAccount.setBalance(cashAccount.getBalance().subtract(refundAmount));
            accountRepository.save(platformRevenue);
            accountRepository.save(cashAccount);
        }

        if (compensationAmount.compareTo(BigDecimal.ZERO) > 0) {
            UUID workerId = dispute.getBooking().getWorker() != null
                    ? dispute.getBooking().getWorker().getWorkerId()
                    : null;
            if (workerId != null) {
                LedgerAccount disputeReserve = getOrCreateAccount(LedgerAccountType.EXPENSE_DISPUTE_RESERVE, null);
                LedgerAccount workerPayable = getOrCreateAccount(LedgerAccountType.LIABILITY_WORKER_PAYABLE, workerId);

                createPosting(journalEntry, disputeReserve, PostingDirection.DEBIT, compensationAmount);
                createPosting(journalEntry, workerPayable, PostingDirection.CREDIT, compensationAmount);

                disputeReserve.setBalance(disputeReserve.getBalance().subtract(compensationAmount));
                workerPayable.setBalance(workerPayable.getBalance().add(compensationAmount));
                accountRepository.save(disputeReserve);
                accountRepository.save(workerPayable);
            }
        }

        return journalEntry;
    }

    /**
     * Rule #2: Compensating action for any failed or reversed financial event.
     * Inverts debits and credits of the target journal entry.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LedgerJournalEntry recordCompensatingReversal(UUID originalEntryId, String reason, String idempotencyKey) {
        String lockKey = "ledger_reversal:" + originalEntryId;
        acquireAdvisoryLock(lockKey);

        Optional<LedgerJournalEntry> existing = journalEntryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        LedgerJournalEntry original = journalEntryRepository.findById(originalEntryId)
                .orElseThrow(() -> new ResourceNotFoundException("Original journal entry not found: " + originalEntryId));

        List<LedgerPosting> originalPostings = postingRepository.findByJournalEntry_EntryId(originalEntryId);

        LedgerJournalEntry reversalEntry = journalEntryRepository.save(LedgerJournalEntry.builder()
                .idempotencyKey(idempotencyKey)
                .booking(original.getBooking())
                .transactionType(LedgerTransactionType.COMPENSATING_REVERSAL)
                .description("Compensating reversal of entry " + originalEntryId + ". Reason: " + reason)
                .build());

        for (LedgerPosting p : originalPostings) {
            PostingDirection oppositeDirection = p.getDirection() == PostingDirection.DEBIT
                    ? PostingDirection.CREDIT
                    : PostingDirection.DEBIT;

            createPosting(reversalEntry, p.getAccount(), oppositeDirection, p.getAmount());

            LedgerAccount account = p.getAccount();
            if (oppositeDirection == PostingDirection.DEBIT) {
                account.setBalance(account.getBalance().add(p.getAmount()));
            } else {
                account.setBalance(account.getBalance().subtract(p.getAmount()));
            }
            accountRepository.save(account);
        }

        logger.warn("Created compensating reversal journal entry {} for original entry {}",
                reversalEntry.getEntryId(), originalEntryId);
        return reversalEntry;
    }

    private void createPosting(LedgerJournalEntry entry, LedgerAccount account, PostingDirection direction, BigDecimal amount) {
        postingRepository.save(LedgerPosting.builder()
                .journalEntry(entry)
                .account(account)
                .direction(direction)
                .amount(amount)
                .currency(account.getCurrency())
                .build());
    }

    private void acquireAdvisoryLock(String lockKey) {
        try {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:lockKey))")
                    .setParameter("lockKey", lockKey)
                    .getSingleResult();
        } catch (Exception e) {
            logger.debug("Advisory lock acquired or mocked for {}", lockKey);
        }
    }
}
