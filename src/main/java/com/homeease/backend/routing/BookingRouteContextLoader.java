package com.homeease.backend.routing;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.repository.BookingRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** Reads the persisted booking facts for routing inside a short read-only transaction. */
@Component
public class BookingRouteContextLoader {

    private final BookingRepository bookingRepository;

    public BookingRouteContextLoader(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Transactional(readOnly = true)
    public Optional<RouteContext> load(UUID bookingId) {
        return bookingRepository.findById(bookingId).map(this::toContext);
    }

    private RouteContext toContext(Booking booking) {
        Worker worker = booking.getWorker();
        UUID workerUserId = worker != null && worker.getUser() != null ? worker.getUser().getUserId() : null;
        UUID workerEntityId = worker != null ? worker.getWorkerId() : null;
        UUID customerUserId = booking.getUser() != null ? booking.getUser().getUserId() : null;
        // Persisted service address coordinates (bookings.user_lat / user_lng).
        return new RouteContext(booking.getBookingId(), booking.getStatus(), customerUserId,
                workerUserId, workerEntityId, booking.getUserLat(), booking.getUserLng());
    }
}
