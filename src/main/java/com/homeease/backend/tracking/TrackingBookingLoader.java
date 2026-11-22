package com.homeease.backend.tracking;

import com.homeease.backend.model.entity.Booking;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.repository.BookingRepository;
import com.homeease.backend.repository.ReviewRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Persisted booking facts for the tracking API, read in one short read-only transaction. Callers do their Redis
 * reads AFTER this returns, so no database connection is ever held while waiting on Redis.
 */
@Component
public class TrackingBookingLoader {

    public record BookingFacts(UUID bookingId,
                               BookingStage stage,
                               UUID customerUserId,
                               UUID workerUserId,
                               UUID workerEntityId,
                               String workerName,
                               String workerPhone,
                               String workerAvatarUrl,
                               Double workerRating,
                               long workerRatingCount,
                               String serviceName,
                               Double destLat,
                               Double destLng) {

        public boolean isCustomer(UUID userId) {
            return userId != null && userId.equals(customerUserId);
        }

        public boolean isAssignedWorker(UUID userId) {
            return userId != null && (userId.equals(workerUserId) || userId.equals(workerEntityId));
        }
    }

    private final BookingRepository bookingRepository;
    private final ReviewRepository reviewRepository;

    public TrackingBookingLoader(BookingRepository bookingRepository, ReviewRepository reviewRepository) {
        this.bookingRepository = bookingRepository;
        this.reviewRepository = reviewRepository;
    }

    @Transactional(readOnly = true)
    public Optional<BookingFacts> load(UUID bookingId) {
        return bookingRepository.findById(bookingId).map(this::toFacts);
    }

    private BookingFacts toFacts(Booking b) {
        Worker w = b.getWorker();
        UUID workerUserId = w != null && w.getUser() != null ? w.getUser().getUserId() : null;
        long ratingCount = w != null ? reviewRepository.countByWorker_WorkerId(w.getWorkerId()) : 0L;
        // Worker.rating defaults to 4.8 for every new partner; without a single review it is not a rating.
        Double rating = w != null && ratingCount > 0 ? w.getRating() : null;
        return new BookingFacts(
                b.getBookingId(),
                b.getStatus(),
                b.getUser() != null ? b.getUser().getUserId() : null,
                workerUserId,
                w != null ? w.getWorkerId() : null,
                w != null && w.getUser() != null ? w.getUser().getFullName() : null,
                w != null && w.getUser() != null ? w.getUser().getPhoneNumber() : null,
                w != null ? w.getAvatarUrl() : null,
                rating,
                ratingCount,
                b.getService() != null ? b.getService().getName() : null,
                b.getUserLat(),
                b.getUserLng());
    }
}
