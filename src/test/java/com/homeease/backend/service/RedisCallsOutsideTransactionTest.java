package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.repository.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A database connection must not be held while waiting on Redis. The Hikari pool is 20 connections and Redis
 * commands may take up to the command timeout (2 s) when the remote Redis is slow, so a ping method that wraps its
 * Redis calls in a transaction can pin the whole pool under a Redis brownout.
 *
 * <p>Spring's real @Transactional proxying is used (a recording transaction manager stands in for JPA), and the
 * Redis-facing collaborator records whether a transaction was active at the moment it was called.
 */
class RedisCallsOutsideTransactionTest {

    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition def) { CALLS.add("tx-begin"); }
        @Override protected void doCommit(DefaultTransactionStatus status) { CALLS.add("tx-commit"); }
        @Override protected void doRollback(DefaultTransactionStatus status) { CALLS.add("tx-rollback"); }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Cfg {
        final UUID workerId = UUID.randomUUID();

        @Bean PlatformTransactionManager transactionManager() { return new RecordingTransactionManager(); }

        @Bean WorkerRepository workerRepository() {
            WorkerRepository repo = mock(WorkerRepository.class);
            Worker w = Worker.builder().workerId(workerId).isOnline(true).isVerified(true)
                    .lastLocationUpdatedAt(Instant.now()).build();
            when(repo.findById(workerId)).thenReturn(Optional.of(w));
            when(repo.findByUser_UserId(workerId)).thenReturn(Optional.of(w));
            when(repo.save(any(Worker.class))).thenAnswer(i -> i.getArgument(0));
            return repo;
        }

        /** Records whether a transaction is active when Redis is touched. */
        @Bean WorkerPresenceService presence() {
            WorkerPresenceService p = mock(WorkerPresenceService.class);
            when(p.isEnforced()).thenReturn(true);
            org.mockito.stubbing.Answer<Object> record = inv -> {
                CALLS.add("redis:" + inv.getMethod().getName() + ":tx="
                        + TransactionSynchronizationManager.isActualTransactionActive());
                return null;
            };
            org.mockito.Mockito.doAnswer(record).when(p).registerPresence(any(), anyDouble(), anyDouble(), any(), any());
            return p;
        }

        @Bean FraudDetectionService fraud() {
            FraudDetectionService f = mock(FraudDetectionService.class);
            FraudDetectionService.TelemetryValidationResult ok = mock(FraudDetectionService.TelemetryValidationResult.class);
            when(ok.isValid()).thenReturn(true);
            when(f.validateWorkerLocation(any(), anyDouble(), anyDouble(), anyDouble(), anyLong(), any())).thenAnswer(inv -> {
                CALLS.add("redis:fraud:tx=" + TransactionSynchronizationManager.isActualTransactionActive());
                return ok;
            });
            return f;
        }

        @Bean WorkerStreamService workerStreamService(WorkerRepository repo, FraudDetectionService fraud,
                                                      WorkerPresenceService presence) {
            return new WorkerStreamService(mock(RedisTemplate.class), repo,
                    mock(org.springframework.messaging.simp.SimpMessagingTemplate.class), fraud, presence);
        }

        @Bean WorkerService workerService(WorkerRepository repo, WorkerPresenceService presence) {
            return new WorkerService(repo, mock(UserRepository.class), mock(SubServiceRepository.class),
                    mock(WorkerServiceLinkRepository.class), mock(PaymentRepository.class), mock(ReviewRepository.class),
                    mock(RedisTemplate.class), presence);
        }
    }

    private void withContext(java.util.function.Consumer<org.springframework.context.ApplicationContext> body) {
        CALLS.clear();
        new ApplicationContextRunner().withUserConfiguration(Cfg.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            body.accept(ctx);
        });
    }

    @Test
    void the_worker_ping_does_its_redis_work_outside_any_database_transaction() {
        withContext(ctx -> {
            UUID id = ctx.getBean(Cfg.class).workerId;
            ctx.getBean(WorkerStreamService.class).recordWorkerPing(id, 12.9, 77.6, "560001", 8.0);

            System.out.println("[tx-boundary] ping: " + CALLS);
            assertThat(CALLS).contains("redis:fraud:tx=false", "redis:registerPresence:tx=false");
            assertThat(CALLS).as("no Redis call may run while a transaction is open")
                    .noneMatch(c -> c.startsWith("redis:") && c.endsWith("tx=true"));
        });
    }

    @Test
    void the_location_update_does_its_redis_work_outside_any_database_transaction() {
        withContext(ctx -> {
            UUID id = ctx.getBean(Cfg.class).workerId;
            ctx.getBean(WorkerService.class).updateLiveLocation(id, 12.9, 77.6);

            System.out.println("[tx-boundary] location: " + CALLS);
            assertThat(CALLS).contains("redis:registerPresence:tx=false");
            assertThat(CALLS).noneMatch(c -> c.startsWith("redis:") && c.endsWith("tx=true"));
        });
    }
}
