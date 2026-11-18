package com.homeease.backend.service;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.BookingServiceItemRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The recovery job is new production behaviour, so prove the whole wiring and not just the method: with scheduling
 * enabled (as {@code HomeEaseBackendApplication} does) and NO recovery property set except a faster clock, Spring
 * itself must create the bean, resolve every default, run it on a schedule and hand a stuck booking to dispatch.
 */
class InstantDispatchRecoverySchedulingTest {

    @Configuration
    @EnableScheduling
    static class SchedulingOn { }

    @Test
    @DisplayName("enabled by default, scheduled by Spring, and it re-dispatches a stuck booking with its first sub-service")
    void theJobRunsOnItsOwn() {
        BookingRepository bookings = mock(BookingRepository.class);
        BookingServiceItemRepository items = mock(BookingServiceItemRepository.class);
        BookingAssignmentService assignment = mock(BookingAssignmentService.class);
        UUID stuckBooking = UUID.randomUUID();
        UUID subService = UUID.randomUUID();
        when(bookings.findUndispatchedInstantBookingIds(any(), any(), any())).thenReturn(List.of(stuckBooking)).thenReturn(List.of());
        when(items.findSubServiceIdsByBookingId(stuckBooking)).thenReturn(List.of(subService));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // only the clock is sped up; the "enabled" switch and every other default are left to Spring
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fast-clock", Map.of(
                    "homeease.dispatch.recovery.initial-delay-ms", "10",
                    "homeease.dispatch.recovery.interval-ms", "50")));
            context.registerBean(BookingRepository.class, () -> bookings);
            context.registerBean(BookingServiceItemRepository.class, () -> items);
            context.registerBean(BookingAssignmentService.class, () -> assignment);
            context.register(SchedulingOn.class, InstantDispatchRecoveryJob.class);
            context.refresh();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(
                    () -> verify(assignment).dispatchInstantBookingById(stuckBooking, subService));
        }
    }

    @Test
    @DisplayName("can be switched off with homeease.dispatch.recovery.enabled=false")
    void theJobCanBeDisabled() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("off", Map.of(
                    "homeease.dispatch.recovery.enabled", "false")));
            context.registerBean(BookingRepository.class, () -> mock(BookingRepository.class));
            context.registerBean(BookingServiceItemRepository.class, () -> mock(BookingServiceItemRepository.class));
            context.registerBean(BookingAssignmentService.class, () -> mock(BookingAssignmentService.class));
            context.register(InstantDispatchRecoveryJob.class);
            context.refresh();

            org.assertj.core.api.Assertions.assertThat(context.getBeansOfType(InstantDispatchRecoveryJob.class)).isEmpty();
        }
    }
}
