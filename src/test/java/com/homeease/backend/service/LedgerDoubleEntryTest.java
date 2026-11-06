package com.homeease.backend.service;

import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerDoubleEntryTest {

    @Mock
    private LedgerAccountRepository accountRepository;

    @Mock
    private LedgerJournalEntryRepository journalEntryRepository;

    @Mock
    private LedgerPostingRepository postingRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query nativeQuery;

    private LedgerService ledgerService;

    private UUID bookingId;
    private UUID workerId;
    private Booking booking;
    private Worker worker;
    private LedgerAccount escrowAccount;
    private LedgerAccount workerPayableAccount;
    private LedgerAccount platformRevenueAccount;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(accountRepository, journalEntryRepository, postingRepository);
        ReflectionTestUtils.setField(ledgerService, "entityManager", entityManager);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.getSingleResult()).thenReturn(1);

        bookingId = UUID.randomUUID();
        workerId = UUID.randomUUID();

        worker = Worker.builder()
                .workerId(workerId)
                .user(User.builder().userId(UUID.randomUUID()).fullName("Partner One").build())
                .build();

        booking = Booking.builder()
                .bookingId(bookingId)
                .worker(worker)
                .totalAmount(BigDecimal.valueOf(500.00))
                .build();

        escrowAccount = LedgerAccount.builder()
                .accountId(UUID.randomUUID())
                .accountType(LedgerAccountType.LIABILITY_CUSTOMER_ESCROW)
                .balance(BigDecimal.valueOf(500.00))
                .currency("INR")
                .build();

        workerPayableAccount = LedgerAccount.builder()
                .accountId(UUID.randomUUID())
                .accountType(LedgerAccountType.LIABILITY_WORKER_PAYABLE)
                .entityId(workerId)
                .balance(BigDecimal.ZERO)
                .currency("INR")
                .build();

        platformRevenueAccount = LedgerAccount.builder()
                .accountId(UUID.randomUUID())
                .accountType(LedgerAccountType.REVENUE_PLATFORM_FEE)
                .balance(BigDecimal.ZERO)
                .currency("INR")
                .build();
    }

    @Test
    @DisplayName("Job settlement strictly enforces Debits == Credits and balances ledger")
    void testJobSettlementBalances() {
        when(accountRepository.findByAccountTypeAndEntityIdIsNull(LedgerAccountType.LIABILITY_CUSTOMER_ESCROW))
                .thenReturn(Optional.of(escrowAccount));
        when(accountRepository.findByAccountTypeAndEntityId(LedgerAccountType.LIABILITY_WORKER_PAYABLE, workerId))
                .thenReturn(Optional.of(workerPayableAccount));
        when(accountRepository.findByAccountTypeAndEntityIdIsNull(LedgerAccountType.REVENUE_PLATFORM_FEE))
                .thenReturn(Optional.of(platformRevenueAccount));

        when(journalEntryRepository.findByIdempotencyKey("settle-booking-1")).thenReturn(Optional.empty());

        when(journalEntryRepository.save(any(LedgerJournalEntry.class))).thenAnswer(invocation -> {
            LedgerJournalEntry entry = invocation.getArgument(0);
            entry.setEntryId(UUID.randomUUID());
            return entry;
        });

        List<LedgerPosting> capturedPostings = new ArrayList<>();
        when(postingRepository.save(any(LedgerPosting.class))).thenAnswer(invocation -> {
            LedgerPosting posting = invocation.getArgument(0);
            capturedPostings.add(posting);
            return posting;
        });

        BigDecimal total = BigDecimal.valueOf(500.00);
        BigDecimal workerShare = BigDecimal.valueOf(400.00);
        BigDecimal platformFee = BigDecimal.valueOf(100.00);

        LedgerJournalEntry result = ledgerService.recordJobSettlement(booking, total, workerShare, platformFee, "settle-booking-1");

        assertNotNull(result);
        assertEquals(3, capturedPostings.size());

        // Verify Double-Entry constraint: Sum(Debits) == Sum(Credits)
        BigDecimal totalDebits = capturedPostings.stream()
                .filter(p -> p.getDirection() == PostingDirection.DEBIT)
                .map(LedgerPosting::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = capturedPostings.stream()
                .filter(p -> p.getDirection() == PostingDirection.CREDIT)
                .map(LedgerPosting::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(totalDebits, totalCredits, "Double entry invariant violated: Debits must equal Credits");
        assertEquals(BigDecimal.valueOf(500.00), totalDebits);

        // Verify account balance updates
        assertEquals(0, escrowAccount.getBalance().compareTo(BigDecimal.ZERO));
        assertEquals(0, workerPayableAccount.getBalance().compareTo(BigDecimal.valueOf(400.00)));
        assertEquals(0, platformRevenueAccount.getBalance().compareTo(BigDecimal.valueOf(100.00)));
    }

    @Test
    @DisplayName("Concurrent settlement with duplicate idempotency key prevents double payouts (Rule #2)")
    void testConcurrentSettlementIdempotency() throws InterruptedException {
        when(accountRepository.findByAccountTypeAndEntityIdIsNull(LedgerAccountType.LIABILITY_CUSTOMER_ESCROW))
                .thenReturn(Optional.of(escrowAccount));
        when(accountRepository.findByAccountTypeAndEntityId(LedgerAccountType.LIABILITY_WORKER_PAYABLE, workerId))
                .thenReturn(Optional.of(workerPayableAccount));
        when(accountRepository.findByAccountTypeAndEntityIdIsNull(LedgerAccountType.REVENUE_PLATFORM_FEE))
                .thenReturn(Optional.of(platformRevenueAccount));

        String sharedIdempotencyKey = "settle-concurrent-" + bookingId;

        LedgerJournalEntry existingEntry = LedgerJournalEntry.builder()
                .entryId(UUID.randomUUID())
                .idempotencyKey(sharedIdempotencyKey)
                .transactionType(LedgerTransactionType.JOB_SETTLEMENT)
                .build();

        AtomicInteger callCount = new AtomicInteger(0);
        when(journalEntryRepository.findByIdempotencyKey(sharedIdempotencyKey)).thenAnswer(inv -> {
            if (callCount.incrementAndGet() > 1) {
                return Optional.of(existingEntry);
            }
            return Optional.empty();
        });

        when(journalEntryRepository.save(any(LedgerJournalEntry.class))).thenReturn(existingEntry);

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        List<LedgerJournalEntry> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    LedgerJournalEntry entry = ledgerService.recordJobSettlement(
                            booking, BigDecimal.valueOf(500.00), BigDecimal.valueOf(400.00),
                            BigDecimal.valueOf(100.00), sharedIdempotencyKey);
                    results.add(entry);
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threads, results.size());
        // Verify that journalEntryRepository.save was only invoked at most once for new entry
        verify(journalEntryRepository, atMost(1)).save(any(LedgerJournalEntry.class));
    }
}
