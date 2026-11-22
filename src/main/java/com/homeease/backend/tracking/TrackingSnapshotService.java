package com.homeease.backend.tracking;

import com.homeease.backend.exception.TrackingApiException;
import com.homeease.backend.exception.TrackingApiException.Code;
import com.homeease.backend.model.enums.BookingStage;
import com.homeease.backend.repository.BookingLocationHistoryRepository;
import com.homeease.backend.routing.GeoMath;
import com.homeease.backend.security.VerifiedCaller;
import com.homeease.backend.service.BookingOtpService;
import com.homeease.backend.tracking.LatestFixReader.LatestFix;
import com.homeease.backend.tracking.TrackingBookingLoader.BookingFacts;
import com.homeease.backend.tracking.TrackingPhaseResolver.DeviceGpsState;
import com.homeease.backend.tracking.TrackingPhaseResolver.Resolution;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the authoritative live-tracking view for {@code GET /api/{v1/}tracking/{bookingId}} and the PostgreSQL
 * history for {@code GET .../history}. Authorization: the booking's customer, its assigned partner, or an admin,
 * identified by a verified JWT only.
 *
 * <p>What is deliberately NOT here: a road distance or ETA. Those come only from the provider-backed route endpoint
 * ({@code GET .../route}); this view reports the straight-line distance labelled as such, never as an ETA.</p>
 */
@Service
public class TrackingSnapshotService {

    /** Stages where the partner's live position may be shown at all. */
    private static final Set<BookingStage> POSITION_VISIBLE =
            EnumSet.of(BookingStage.ACCEPTED, BookingStage.CONFIRMED, BookingStage.ON_THE_WAY);

    private final TrackingBookingLoader loader;
    private final LatestFixReader fixReader;
    private final DeviceStatusStore deviceStatus;
    private final BookingOtpService otpService;
    private final BookingLocationHistoryRepository historyRepository;
    private final TrackingPhaseResolver resolver;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TrackingSnapshotService(TrackingBookingLoader loader, LatestFixReader fixReader, DeviceStatusStore deviceStatus,
                                   BookingOtpService otpService, BookingLocationHistoryRepository historyRepository,
                                   ArrivalDetectionService arrivalDetection,
                                   @org.springframework.beans.factory.annotation.Value("${homeease.tracking.live-window-seconds:30}") long liveWindowSeconds) {
        this(loader, fixReader, deviceStatus, otpService, historyRepository,
                new TrackingPhaseResolver(liveWindowSeconds * 1000L, arrivalDetection.geofenceConfig().nearbyRadiusM()),
                Clock.systemUTC());
    }

    TrackingSnapshotService(TrackingBookingLoader loader, LatestFixReader fixReader, DeviceStatusStore deviceStatus,
                            BookingOtpService otpService, BookingLocationHistoryRepository historyRepository,
                            TrackingPhaseResolver resolver, Clock clock) {
        this.loader = loader;
        this.fixReader = fixReader;
        this.deviceStatus = deviceStatus;
        this.otpService = otpService;
        this.historyRepository = historyRepository;
        this.resolver = resolver;
        this.clock = clock;
    }

    /** Loads the booking and checks the caller is a participant. */
    public BookingFacts requireParticipant(UUID bookingId, VerifiedCaller caller) {
        BookingFacts f = loader.load(bookingId)
                .orElseThrow(() -> new TrackingApiException(Code.BOOKING_NOT_FOUND, "Booking not found."));
        if (!caller.isAdmin() && !f.isCustomer(caller.userId()) && !f.isAssignedWorker(caller.userId())) {
            throw new TrackingApiException(Code.NOT_BOOKING_PARTICIPANT, "You are not allowed to track this booking.");
        }
        return f;
    }

    /** Loads the booking and checks the caller is its assigned partner (or an admin). */
    public BookingFacts requireAssignedWorker(UUID bookingId, VerifiedCaller caller) {
        BookingFacts f = requireParticipant(bookingId, caller);
        if (!caller.isAdmin() && !f.isAssignedWorker(caller.userId())) {
            throw new TrackingApiException(Code.ROLE_NOT_ALLOWED, "Only the assigned service partner can do this.");
        }
        return f;
    }

    public Map<String, Object> snapshot(UUID bookingId, VerifiedCaller caller) {
        BookingFacts f = requireParticipant(bookingId, caller);
        long now = clock.millis();

        // Redis reads happen here, after the read-only DB transaction has ended.
        Optional<LatestFix> fix = POSITION_VISIBLE.contains(f.stage()) ? fixReader.read(bookingId) : Optional.empty();
        fix = fix.filter(LatestFix::trackingActive);
        DeviceGpsState device = f.stage() == BookingStage.ON_THE_WAY ? deviceStatus.read(bookingId) : DeviceGpsState.UNKNOWN;

        boolean destUsable = GeoMath.isUsableCoordinate(f.destLat(), f.destLng());
        Double straightLine = fix.isPresent() && destUsable
                ? GeoMath.haversineMeters(fix.get().lat(), fix.get().lng(), f.destLat(), f.destLng())
                : null;

        Resolution r = resolver.resolve(new TrackingPhaseResolver.Input(
                f.stage(), fix.map(LatestFix::receivedAtMs).orElse(null), straightLine, device, now));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookingId", bookingId);
        body.put("bookingStatus", f.stage() == null ? null : f.stage().name());
        body.put("phase", r.phase().name());
        body.put("live", r.live());
        body.put("liveWindowSeconds", resolver.liveWindowMs() / 1000);
        body.put("lastUpdateAt", fix.map(x -> Instant.ofEpochMilli(x.receivedAtMs()).toString()).orElse(null));
        body.put("lastUpdateAgeSeconds", r.lastFixAgeMs() == null ? null : r.lastFixAgeMs() / 1000);
        body.put("deviceGpsState", device.name());

        if (fix.isPresent()) {
            LatestFix x = fix.get();
            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("lat", x.lat());
            loc.put("lng", x.lng());
            loc.put("heading", x.headingDeg());
            loc.put("speedMps", x.speedMps());
            loc.put("accuracyM", x.accuracyM());
            loc.put("recordedAtEpochMs", x.deviceTimestampMs());
            loc.put("receivedAtEpochMs", x.receivedAtMs());
            body.put("workerLocation", loc);
        } else {
            body.put("workerLocation", null);
        }
        body.put("destination", destUsable ? Map.of("lat", f.destLat(), "lng", f.destLng()) : null);
        body.put("straightLineDistanceMeters", straightLine == null ? null : Math.round(straightLine));

        Map<String, Object> worker = new LinkedHashMap<>();
        worker.put("name", f.workerName());
        worker.put("avatarUrl", f.workerAvatarUrl());
        worker.put("rating", f.workerRating());
        worker.put("ratingCount", f.workerRatingCount());
        worker.put("serviceCategory", f.serviceName());
        boolean customerView = f.isCustomer(caller.userId()) || caller.isAdmin();
        if (customerView) {
            worker.put("phone", f.workerPhone());
        }
        body.put("worker", f.workerEntityId() == null ? null : worker);

        if (customerView) {
            boolean arrived = f.stage() == BookingStage.ARRIVED;
            Optional<Instant> locked = arrived ? otpService.lockedUntil(bookingId) : Optional.empty();
            body.put("pinAvailable", arrived && locked.isEmpty());
            body.put("pinLockedUntil", locked.map(Instant::toString).orElse(null));
        }
        body.put("serverTime", Instant.ofEpochMilli(now).toString());
        return body;
    }

    public Map<String, Object> history(UUID bookingId, VerifiedCaller caller, int limit) {
        requireParticipant(bookingId, caller);
        int bounded = Math.max(1, Math.min(limit, 2_000));
        List<Map<String, Object>> points = historyRepository.findForBooking(bookingId, PageRequest.of(0, bounded))
                .stream()
                .map(h -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("lat", h.getLat());
                    m.put("lng", h.getLng());
                    m.put("accuracyM", h.getAccuracyM());
                    m.put("speedMps", h.getSpeedMps());
                    m.put("heading", h.getHeadingDeg());
                    m.put("recordedAt", h.getRecordedAt().toString());
                    return m;
                })
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookingId", bookingId);
        body.put("count", points.size());
        body.put("limit", bounded);
        body.put("source", "postgresql");
        body.put("points", points);
        return body;
    }
}
