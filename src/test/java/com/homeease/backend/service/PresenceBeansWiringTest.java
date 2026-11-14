package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.WorkerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Proves that Spring binds the property names documented for operators (the env vars map to these keys through
 * application.yml) and that startup validation of the policy actually runs when the beans are created.
 */
class PresenceBeansWiringTest {

    @SuppressWarnings("unchecked")
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(RedisTemplate.class, () -> mock(RedisTemplate.class))
                .withBean(WorkerRepository.class, () -> mock(WorkerRepository.class))
                .withBean(BookingRepository.class, () -> mock(BookingRepository.class))
                .withBean(WorkerPresenceService.class)
                .withBean(WorkerAcceptanceGuard.class);
    }

    @Test
    void defaults_start_cleanly_with_the_documented_policy() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            WorkerPresenceService p = ctx.getBean(WorkerPresenceService.class);
            assertThat(p.heartbeatIntervalSeconds()).isEqualTo(15);
            assertThat(p.heartbeatTtlSeconds()).isEqualTo(60);
            assertThat(p.isEnforced()).as("presence enforcement is OPT-IN: off unless configured").isFalse();
            assertThat(ctx).hasSingleBean(WorkerAcceptanceGuard.class);
        });
    }

    @Test
    void configured_values_are_bound_and_a_valid_stationary_setup_is_accepted() {
        runner().withPropertyValues(
                        "homeease.worker.presence.heartbeat-interval-seconds=20",
                        "homeease.worker.presence.heartbeat-ttl-seconds=90",
                        "homeease.worker.presence.enforce=true",
                        "homeease.worker.presence.stationary.enabled=true",
                        "homeease.worker.presence.stationary.max-move-meters=30",
                        "homeease.worker.presence.stationary.full-refresh-seconds=120")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    WorkerPresenceService p = ctx.getBean(WorkerPresenceService.class);
                    assertThat(p.heartbeatIntervalSeconds()).isEqualTo(20);
                    assertThat(p.heartbeatTtlSeconds()).isEqualTo(90);
                    assertThat(p.isEnforced()).as("explicitly enabled").isTrue();
                });
    }

    @Test
    void a_ttl_shorter_than_three_intervals_prevents_startup() {
        runner().withPropertyValues(
                        "homeease.worker.presence.heartbeat-interval-seconds=30",
                        "homeease.worker.presence.heartbeat-ttl-seconds=60")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("WORKER_HEARTBEAT_TTL_SECONDS");
                });
    }

    @Test
    void an_invalid_stationary_setup_prevents_startup() {
        runner().withPropertyValues(
                        "homeease.worker.presence.stationary.enabled=true",
                        "homeease.worker.presence.stationary.full-refresh-seconds=5") // below the 15 s interval
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("FULL_REFRESH_SECONDS");
                });
    }

    private static com.homeease.backend.model.entity.Booking instantBooking() {
        return com.homeease.backend.model.entity.Booking.builder().bookingId(java.util.UUID.randomUUID())
                .bookingType(com.homeease.backend.model.enums.BookingType.INSTANT).build();
    }

    @Test
    void the_acceptance_guard_is_OFF_by_default_so_deploying_does_not_change_acceptance() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // off = a no-op even for an instant booking: no repository interaction at all
            ctx.getBean(WorkerAcceptanceGuard.class).ensureWorkerFree(java.util.UUID.randomUUID(), instantBooking());
            org.mockito.Mockito.verifyNoInteractions(ctx.getBean(WorkerRepository.class), ctx.getBean(BookingRepository.class));
        });
    }

    @Test
    void the_acceptance_guard_becomes_active_only_when_explicitly_enabled() {
        runner().withPropertyValues("homeease.dispatch.worker-accept-guard.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            java.util.UUID worker = java.util.UUID.randomUUID();
            ctx.getBean(WorkerAcceptanceGuard.class).ensureWorkerFree(worker, instantBooking());
            // enabled = the per-worker advisory lock is taken before the busy check
            org.mockito.Mockito.verify(ctx.getBean(WorkerRepository.class)).lockWorkerAcceptance("worker_accept:" + worker);
        });
    }
}
