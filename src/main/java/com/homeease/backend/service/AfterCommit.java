package com.homeease.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs side effects (STOMP pushes, FCM, follow-up dispatch) only once the surrounding database transaction has
 * COMMITTED.
 *
 * <p>Why this exists: telling a worker about an assignment that is later rolled back, or that is not yet visible to
 * any other connection (the worker's app may call {@code GET /workers/me/requests} the instant the push arrives), is
 * a correctness bug. Work registered here is dropped if the transaction rolls back.
 *
 * <p>Semantics:
 * <ul>
 *   <li>Inside an active transaction: the action runs right after a successful commit.</li>
 *   <li>Outside any transaction (plain unit tests, background threads): the action runs immediately.</li>
 *   <li>An exception from the action is logged and swallowed. It must never turn an already-committed booking into
 *       an error for the caller, and it can never roll the committed transaction back.</li>
 *   <li>When a {@link PlatformTransactionManager} is supplied, the action runs in its OWN new transaction
 *       ({@code REQUIRES_NEW}). That is required whenever the action writes to the database (for example FCM
 *       notification rows): Spring documents that data access executed from {@code afterCommit} would otherwise
 *       still "participate" in the already-committed transaction, and its writes would silently never be committed.</li>
 * </ul>
 */
final class AfterCommit {

    private static final Logger logger = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    /** True while a real transaction is open on this thread, i.e. work scheduled now would have to wait for commit. */
    static boolean inTransaction() {
        return TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive();
    }

    /** Runs {@code action} after commit (or immediately when there is no transaction); no database access inside. */
    static void run(String description, Runnable action) {
        run(null, description, action);
    }

    /**
     * Runs {@code action} after commit (or immediately when there is no transaction). If {@code txManager} is not
     * null the action gets its own {@code REQUIRES_NEW} transaction, so it may safely write to the database.
     */
    static void run(PlatformTransactionManager txManager, String description, Runnable action) {
        Runnable task = txManager == null ? action : () -> inNewTransaction(txManager, action);
        if (inTransaction()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    execute(description, task);
                }
            });
        } else {
            execute(description, task);
        }
    }

    private static void inNewTransaction(PlatformTransactionManager txManager, Runnable action) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> action.run());
    }

    private static void execute(String description, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            logger.warn("Post-commit action '{}' failed (the transaction itself had already committed): {}",
                    description, e.toString());
        }
    }
}
