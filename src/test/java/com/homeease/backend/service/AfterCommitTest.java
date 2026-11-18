package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * The contract every "notify the worker / customer" call in the dispatch flow relies on: nothing is sent before the
 * transaction has committed, nothing is sent at all if it rolls back, and a failing notification can never reach back
 * into the commit.
 *
 * <p>A transaction is simulated with Spring's own synchronization manager and its own commit / completion triggers
 * (the exact calls {@code AbstractPlatformTransactionManager} makes), so no database is involved.
 */
class AfterCommitTest {

    @AfterEach
    void leaveNoTransactionBehind() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void commit() {
        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }

    private static void rollback() {
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    @Test
    @DisplayName("with no transaction (unit tests, background threads) the action simply runs immediately")
    void runsImmediately_whenThereIsNoTransaction() {
        List<String> events = new ArrayList<>();

        AfterCommit.run("no tx", () -> events.add("sent"));

        assertThat(events).containsExactly("sent");
    }

    @Test
    @DisplayName("inside a transaction the action waits for the commit and then runs exactly once")
    void defersUntilCommit() {
        List<String> events = new ArrayList<>();
        beginTransaction();

        AfterCommit.run("deferred", () -> events.add("sent"));
        assertThat(events).as("nothing may happen while the transaction is still open").isEmpty();

        commit();
        assertThat(events).containsExactly("sent");
    }

    @Test
    @DisplayName("if the transaction rolls back the action never runs")
    void isDroppedOnRollback() {
        List<String> events = new ArrayList<>();
        beginTransaction();

        AfterCommit.run("dropped", () -> events.add("sent"));
        rollback();

        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a failing action is logged and swallowed: it can never fail an already-committed transaction")
    void anExceptionInTheAction_neverReachesTheCommit() {
        List<String> events = new ArrayList<>();
        beginTransaction();

        AfterCommit.run("explodes", () -> { throw new IllegalStateException("FCM is down"); });
        AfterCommit.run("still runs", () -> events.add("second action ran"));

        commit(); // must not throw
        assertThat(events).containsExactly("second action ran");
    }

    @Test
    @DisplayName("an empty (SUPPORTS) transaction with synchronization but nothing to commit counts as 'no transaction'")
    void synchronizationWithoutARealTransaction_runsImmediately() {
        List<String> events = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        AfterCommit.run("supports", () -> events.add("sent"));

        assertThat(AfterCommit.inTransaction()).isFalse();
        assertThat(events).containsExactly("sent");
    }

    @Test
    @DisplayName("with a transaction manager the action gets its OWN new transaction (it may write rows), still only after commit")
    void runsInItsOwnNewTransaction_afterCommit() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        List<String> events = new ArrayList<>();
        beginTransaction();

        AfterCommit.run(txManager, "own tx", () -> events.add("sent"));
        assertThat(events).isEmpty();

        commit();

        assertThat(events).containsExactly("sent");
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .as("REQUIRES_NEW: Spring documents that work done in afterCommit would otherwise join the finished transaction and never be committed")
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(txManager).commit(any());
    }
}
