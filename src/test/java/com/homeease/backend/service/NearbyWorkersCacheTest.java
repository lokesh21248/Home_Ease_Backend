package com.homeease.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.repository.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class NearbyWorkersCacheTest {

    private final UUID svc = UUID.randomUUID();

    @Test
    void repeated_calls_in_the_same_cell_load_once_until_the_ttl_expires() {
        AtomicLong clock = new AtomicLong(0);
        NearbyWorkersCache<String> cache = new NearbyWorkersCache<>(10, 4, 100, clock::get);
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get(svc, null, 12.97161, 77.59461, 5000, () -> { loads.incrementAndGet(); return List.of("w1"); })).containsExactly("w1");
        assertThat(cache.get(svc, null, 12.97163, 77.59463, 5000, () -> { loads.incrementAndGet(); return List.of("w2"); }))
                .as("same ~11 m cell within the TTL").containsExactly("w1");
        assertThat(loads.get()).isEqualTo(1);

        clock.addAndGet(10_001);
        assertThat(cache.get(svc, null, 12.97161, 77.59461, 5000, () -> { loads.incrementAndGet(); return List.of("w3"); }))
                .as("expired -> reloaded").containsExactly("w3");
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void different_service_radius_or_cell_never_share_a_result_and_zero_ttl_disables_caching() {
        AtomicLong clock = new AtomicLong(0);
        NearbyWorkersCache<String> cache = new NearbyWorkersCache<>(10, 4, 100, clock::get);
        AtomicInteger loads = new AtomicInteger();
        java.util.function.Supplier<List<String>> loader = () -> List.of("n" + loads.incrementAndGet());

        cache.get(svc, null, 12.9716, 77.5946, 5000, loader);
        cache.get(UUID.randomUUID(), null, 12.9716, 77.5946, 5000, loader);   // other service
        cache.get(svc, null, 12.9716, 77.5946, 8000, loader);                 // other radius
        cache.get(svc, null, 12.9816, 77.5946, 5000, loader);                 // ~1.1 km away
        assertThat(loads.get()).isEqualTo(4);

        NearbyWorkersCache<String> off = new NearbyWorkersCache<>(0, 4, 100, clock::get);
        off.get(svc, null, 1, 1, 5000, loader);
        off.get(svc, null, 1, 1, 5000, loader);
        assertThat(loads.get()).isEqualTo(6);
        assertThat(off.size()).isZero();
    }

    @Test
    void memory_is_bounded() {
        AtomicLong clock = new AtomicLong(0);
        NearbyWorkersCache<String> cache = new NearbyWorkersCache<>(60, 4, 20, clock::get);
        for (int i = 0; i < 500; i++) {
            cache.get(svc, null, i * 0.01, 0, 5000, () -> List.of("x"));
        }
        assertThat(cache.size()).isLessThanOrEqualTo(20);
    }

    /** The point of the cache: the real service runs discovery (Redis GEO) once for repeated identical requests. */
    @Test
    @SuppressWarnings("unchecked")
    void the_service_runs_discovery_once_for_repeated_calls_and_every_call_when_caching_is_off() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        org.springframework.data.redis.core.GeoOperations<String, String> geo = mock(org.springframework.data.redis.core.GeoOperations.class);
        when(redis.opsForGeo()).thenReturn(geo);
        WorkerRepository workers = mock(WorkerRepository.class);
        when(workers.findByIsOnlineTrueAndIsVerifiedTrue()).thenReturn(List.of());

        BookingAssignmentService svcUnderTest = new BookingAssignmentService(workers, mock(WorkerServiceLinkRepository.class),
                mock(BookingRepository.class), mock(BookingAssignmentRepository.class), mock(ReviewRepository.class),
                mock(NotificationDeliveryRepository.class), redis, mock(FcmNotificationService.class),
                mock(BookingDomainEventService.class), mock(org.springframework.messaging.simp.SimpMessagingTemplate.class),
                mock(BookingOtpService.class), new com.fasterxml.jackson.databind.ObjectMapper(), mock(DispatchAuditLogRepository.class));

        // default for a directly constructed instance: caching OFF -> each call runs discovery
        svcUnderTest.findNearbyAvailableWorkers(svc, null, 12.9716, 77.5946, null);
        svcUnderTest.findNearbyAvailableWorkers(svc, null, 12.9716, 77.5946, null);
        verify(workers, times(2)).findByIsOnlineTrueAndIsVerifiedTrue();

        // what Spring injects in the running application: 10 s
        BookingAssignmentService cached = new BookingAssignmentService(workers, mock(WorkerServiceLinkRepository.class),
                mock(BookingRepository.class), mock(BookingAssignmentRepository.class), mock(ReviewRepository.class),
                mock(NotificationDeliveryRepository.class), redis, mock(FcmNotificationService.class),
                mock(BookingDomainEventService.class), mock(org.springframework.messaging.simp.SimpMessagingTemplate.class),
                mock(BookingOtpService.class), new com.fasterxml.jackson.databind.ObjectMapper(), mock(DispatchAuditLogRepository.class));
        ReflectionTestUtils.setField(cached, "nearbyCacheSeconds", 10);
        org.mockito.Mockito.clearInvocations(workers);
        for (int i = 0; i < 20; i++) {
            cached.findNearbyAvailableWorkers(svc, null, 12.97161, 77.59461, null);
        }
        verify(workers, times(1)).findByIsOnlineTrueAndIsVerifiedTrue();
    }
}
