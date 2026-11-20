package com.homeease.backend.routing;

import com.homeease.backend.model.enums.BookingStage;

import java.util.UUID;

/**
 * Immutable snapshot of the persisted booking facts the route service needs. Loaded in a short read-only
 * transaction so no database connection is held while the routing provider is called.
 *
 * @param destLat / destLng the booking's persisted service-address coordinates (never the caller's GPS)
 */
public record RouteContext(UUID bookingId,
                           BookingStage status,
                           UUID customerUserId,
                           UUID workerUserId,
                           UUID workerEntityId,
                           Double destLat,
                           Double destLng) {

    public boolean hasAssignedWorker() {
        return workerUserId != null || workerEntityId != null;
    }
}
