package com.homeease.backend.service;

import com.homeease.backend.exception.WorkerBusyException;
import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.model.enums.BookingType;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.WorkerRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stops one worker from winning two instant bookings at the same time.
 *
 * <p>Acceptance is already serialised per BOOKING (advisory lock + {@code SELECT ... FOR UPDATE}: exactly one worker
 * wins a given booking). Dispatch also never offers a job to a busy worker. But nothing re-checked at accept time, so
 * a worker who held offers for bookings X and Y could accept both at once, or accept Y while X was already in
 * progress.
 *
 * <p>How it works, inside the caller's transaction:
 * <ol>
 *   <li>take a transaction-scoped Postgres advisory lock keyed by the worker, so two concurrent accepts by the same
 *       worker queue up (the lock is released on commit, so the second one then sees the first one's committed
 *       {@code ACCEPTED} booking);</li>
 *   <li>reject (HTTP 409 {@code WORKER_BUSY}) if the worker already has another booking in an in-flight stage.</li>
 * </ol>
 * Lock order is booking lock, then worker lock, in both accept paths; the first transaction never needs the second
 * booking's lock, so this cannot deadlock.
 *
 * <p>Scope: only when the booking being accepted is an INSTANT booking, i.e. the same rule dispatch already applies
 * ("WORKER_BUSY"). Scheduled bookings are untouched.
 *
 * <p><b>Opt-in</b> ({@code WORKER_ACCEPT_GUARD_ENABLED=true}); OFF by default. It sits on the booking-acceptance path
 * and its lock query has been verified against a real PostgreSQL only at the SQL/protocol level, not through the
 * application's JPA layer, so enable it after the PostGIS integration job in CI has been seen green.
 */
@Component
public class WorkerAcceptanceGuard {

    private static final Logger logger = LoggerFactory.getLogger(WorkerAcceptanceGuard.class);

    /** Same stages dispatch treats as "busy", except CONFIRMED (a future scheduled slot is not "in flight"). */
    static final List<BookingStage> IN_FLIGHT_STAGES = List.of(
            BookingStage.ACCEPTED, BookingStage.ON_THE_WAY, BookingStage.ARRIVED, BookingStage.IN_PROGRESS);

    private final WorkerRepository workerRepository;
    private final BookingRepository bookingRepository;
    private final boolean enabled;

    public WorkerAcceptanceGuard(WorkerRepository workerRepository,
                                 BookingRepository bookingRepository,
                                 @Value("${homeease.dispatch.worker-accept-guard.enabled:false}") boolean enabled) {
        this.workerRepository = workerRepository;
        this.bookingRepository = bookingRepository;
        this.enabled = enabled;
    }

    /**
     * @param workerId the accepting worker
     * @param booking  the booking being accepted (must be loaded under its own lock by the caller)
     * @throws WorkerBusyException if the worker already has another in-flight job
     */
    public void ensureWorkerFree(UUID workerId, Booking booking) {
        if (!enabled || workerId == null || booking == null || booking.getBookingType() != BookingType.INSTANT) {
            return;
        }
        workerRepository.lockWorkerAcceptance("worker_accept:" + workerId);

        List<Booking> inFlight = bookingRepository
                .findByWorker_WorkerIdAndStatusInOrderByScheduledAtAsc(workerId, IN_FLIGHT_STAGES);
        boolean busy = inFlight.stream().anyMatch(b -> !b.getBookingId().equals(booking.getBookingId()));
        if (busy) {
            logger.warn("[WORKER_BUSY] worker {} tried to accept booking {} while holding {} in-flight job(s)",
                    workerId, booking.getBookingId(), inFlight.size());
            throw new WorkerBusyException(
                    "You already have an active job. Finish it before accepting another one.");
        }
    }
}
