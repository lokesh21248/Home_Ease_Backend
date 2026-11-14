package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.WorkerRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;

/**
 * The stale-member sweep is registered through @Scheduled with a SpEL expression over a property. Unit tests that
 * call sweepStaleMembers() directly never exercise that registration, so this uses Spring's real post-processor
 * (without running anything) to read back the interval it computed.
 */
class PresenceSchedulingTest {

    @Configuration
    @EnableScheduling
    static class SchedulingConfig { }

    @SuppressWarnings("unchecked")
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class)
                .withBean(RedisTemplate.class, () -> mock(RedisTemplate.class))
                .withBean(WorkerRepository.class, () -> mock(WorkerRepository.class))
                .withBean(BookingRepository.class, () -> mock(BookingRepository.class))
                .withBean(WorkerPresenceService.class);
    }

    private Duration sweepInterval(org.springframework.context.ApplicationContext ctx) {
        ScheduledAnnotationBeanPostProcessor processor = ctx.getBean(ScheduledAnnotationBeanPostProcessor.class);
        List<FixedDelayTask> sweeps = processor.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(t -> t instanceof FixedDelayTask)
                .map(t -> (FixedDelayTask) t)
                .filter(t -> t.toString().contains("WorkerPresenceService.scheduledSweep"))
                .toList();
        assertThat(sweeps).as("the sweep must be registered exactly once").hasSize(1);
        return sweeps.get(0).getIntervalDuration();
    }

    @Test
    void default_registers_a_900_second_sweep() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(sweepInterval(ctx)).isEqualTo(Duration.ofSeconds(900));
        });
    }

    @Test
    void a_configured_interval_is_honoured() {
        runner().withPropertyValues("homeease.worker.presence.sweep-interval-seconds=120").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(sweepInterval(ctx)).isEqualTo(Duration.ofSeconds(120));
        });
    }

    @Test
    void zero_means_off_and_must_not_become_a_zero_delay_hot_loop() {
        runner().withPropertyValues("homeease.worker.presence.sweep-interval-seconds=0").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(sweepInterval(ctx)).as("a 0 ms fixed delay would re-run the task back to back")
                    .isGreaterThanOrEqualTo(Duration.ofMinutes(10));
        });
    }
}
