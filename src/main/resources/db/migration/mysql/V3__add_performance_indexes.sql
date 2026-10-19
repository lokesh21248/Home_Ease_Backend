-- ====================================================================
-- HomeEase MySQL Migration V3: Database Indexing for High Performance
-- ====================================================================

-- 1. Bookings indexes
CREATE INDEX idx_bookings_user_created ON bookings (user_id, created_at DESC);
CREATE INDEX idx_bookings_worker_created ON bookings (worker_id, created_at DESC);
CREATE INDEX idx_bookings_worker_status ON bookings (worker_id, status);
CREATE INDEX idx_bookings_scheduled_at ON bookings (scheduled_at);
CREATE INDEX idx_bookings_payment_status ON bookings (payment_status);
CREATE INDEX idx_bookings_service_id ON bookings (service_id);

-- 2. Worker & Dispatch indexes
CREATE INDEX idx_worker_services_sub_service ON worker_services (sub_service_id);
CREATE INDEX idx_workers_dispatch_coords ON workers (is_online, is_verified, current_lat, current_lng);

-- 3. Line items
CREATE INDEX idx_booking_services_booking_id ON booking_services (booking_id);
CREATE INDEX idx_booking_services_sub_service_id ON booking_services (sub_service_id);
CREATE INDEX idx_booking_addons_booking_id ON booking_addons (booking_id);
CREATE INDEX idx_booking_addons_addon_id ON booking_addons (addon_id);

-- 4. Assignment attempts
CREATE INDEX idx_assignment_worker_accepted ON assignment_attempts (worker_id, accepted);
CREATE INDEX idx_assignment_booking_id ON assignment_attempts (booking_id);
CREATE INDEX idx_assignment_booking_worker ON assignment_attempts (booking_id, worker_id);

-- 5. Notifications
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_worker_created ON notifications (worker_id, created_at DESC);

-- 6. Reviews
CREATE INDEX idx_reviews_worker_id ON reviews (worker_id);
CREATE INDEX idx_reviews_user_id ON reviews (user_id);
CREATE INDEX idx_reviews_booking_id ON reviews (booking_id);

-- 7. Catalog & Coupons
CREATE INDEX idx_sub_services_service_active ON sub_services (service_id, is_active);
CREATE INDEX idx_service_addons_sub_service_active ON service_addons (sub_service_id, is_active);
CREATE INDEX idx_banners_active ON banners (is_active);
CREATE INDEX idx_coupons_active_validity ON coupons (is_active, valid_from, valid_until);

-- 8. Users & Payments
CREATE INDEX idx_users_role ON users (role);
CREATE INDEX idx_payments_status ON payments (status);
