-- ====================================================================
-- HomeEase PostgreSQL Migration V5: Database Indexing for High Performance
-- Optimizes:
--  1. Booking lookups, history sorting & status filtering
--  2. Spatial dispatch & worker matching
--  3. Order item line fetching (booking_services & booking_addons)
--  4. Polling & dispatch notifications (assignment_attempts & notifications)
--  5. User role filtering & worker review aggregations
-- ====================================================================

-- --------------------------------------------------------------------
-- 1. BOOKINGS TABLE INDEXES
-- --------------------------------------------------------------------
-- Accelerates customer order history: findByUser_UserIdOrderByCreatedAtDesc
CREATE INDEX IF NOT EXISTS idx_bookings_user_created 
    ON bookings (user_id, created_at DESC);

-- Accelerates worker job history: findByWorker_WorkerIdOrderByCreatedAtDesc
CREATE INDEX IF NOT EXISTS idx_bookings_worker_created 
    ON bookings (worker_id, created_at DESC);

-- Accelerates worker active status check: findByWorker_WorkerIdAndStatus
CREATE INDEX IF NOT EXISTS idx_bookings_worker_status 
    ON bookings (worker_id, status);

-- Accelerates scheduling queries, availability slots & reminders
CREATE INDEX IF NOT EXISTS idx_bookings_scheduled_at 
    ON bookings (scheduled_at);

-- Accelerates payment settlement and billing audits
CREATE INDEX IF NOT EXISTS idx_bookings_payment_status 
    ON bookings (payment_status);

-- Accelerates service category revenue and volume analytics
CREATE INDEX IF NOT EXISTS idx_bookings_service_id 
    ON bookings (service_id);


-- --------------------------------------------------------------------
-- 2. WORKER & DISPATCH TABLE INDEXES
-- --------------------------------------------------------------------
-- CRITICAL FOR DISPATCH: worker_services has PK (worker_id, sub_service_id).
-- An index on sub_service_id is mandatory for WHERE ws.sub_service_id = :id
CREATE INDEX IF NOT EXISTS idx_worker_services_sub_service 
    ON worker_services (sub_service_id);

-- Accelerates spatial distance query: filters online & verified workers with valid GPS
CREATE INDEX IF NOT EXISTS idx_workers_online_verified_coords 
    ON workers (is_online, is_verified, current_lat, current_lng)
    WHERE is_online = true AND is_verified = true AND current_lat IS NOT NULL AND current_lng IS NOT NULL;

-- Accelerates unverified worker queue for Admin KYC approvals
CREATE INDEX IF NOT EXISTS idx_workers_unverified 
    ON workers (is_verified)
    WHERE is_verified = false;


-- --------------------------------------------------------------------
-- 3. BOOKING LINE ITEMS & ADDONS
-- --------------------------------------------------------------------
-- Accelerates retrieving invoice line items when loading booking details
CREATE INDEX IF NOT EXISTS idx_booking_services_booking_id 
    ON booking_services (booking_id);

CREATE INDEX IF NOT EXISTS idx_booking_services_sub_service_id 
    ON booking_services (sub_service_id);

CREATE INDEX IF NOT EXISTS idx_booking_addons_booking_id 
    ON booking_addons (booking_id);

CREATE INDEX IF NOT EXISTS idx_booking_addons_addon_id 
    ON booking_addons (addon_id);


-- --------------------------------------------------------------------
-- 4. DISPATCH ASSIGNMENT ATTEMPTS
-- --------------------------------------------------------------------
-- Accelerates worker polling for pending job popups: findByWorker_WorkerIdAndAcceptedIsNull
CREATE INDEX IF NOT EXISTS idx_assignment_worker_pending 
    ON assignment_attempts (worker_id, accepted)
    WHERE accepted IS NULL;

-- Accelerates finding all assignment attempts for a booking
CREATE INDEX IF NOT EXISTS idx_assignment_booking_id 
    ON assignment_attempts (booking_id);

-- Accelerates duplicate assignment check: findByBooking_BookingIdAndWorker_WorkerId
CREATE INDEX IF NOT EXISTS idx_assignment_booking_worker 
    ON assignment_attempts (booking_id, worker_id);


-- --------------------------------------------------------------------
-- 5. NOTIFICATIONS TABLE
-- --------------------------------------------------------------------
-- Accelerates customer notification inbox: findByUser_UserIdOrderByCreatedAtDesc
CREATE INDEX IF NOT EXISTS idx_notifications_user_created 
    ON notifications (user_id, created_at DESC)
    WHERE user_id IS NOT NULL;

-- Accelerates worker notification inbox: findByWorker_WorkerIdOrderByCreatedAtDesc
CREATE INDEX IF NOT EXISTS idx_notifications_worker_created 
    ON notifications (worker_id, created_at DESC)
    WHERE worker_id IS NOT NULL;


-- --------------------------------------------------------------------
-- 6. REVIEWS & RATINGS
-- --------------------------------------------------------------------
-- Accelerates calculating worker average rating & review lists
CREATE INDEX IF NOT EXISTS idx_reviews_worker_id 
    ON reviews (worker_id);

-- Accelerates customer review history
CREATE INDEX IF NOT EXISTS idx_reviews_user_id 
    ON reviews (user_id);

-- Accelerates checking if a booking has already been reviewed
CREATE INDEX IF NOT EXISTS idx_reviews_booking_id 
    ON reviews (booking_id);


-- --------------------------------------------------------------------
-- 7. SERVICE CATALOG & CAROUSELS (CUSTOMER BROWSING)
-- --------------------------------------------------------------------
-- Accelerates loading sub-services by category: findByService_ServiceIdAndIsActiveTrue
CREATE INDEX IF NOT EXISTS idx_sub_services_service_active 
    ON sub_services (service_id, is_active);

-- Accelerates loading addons for a sub-service: findBySubService_SubServiceIdAndIsActiveTrue
CREATE INDEX IF NOT EXISTS idx_service_addons_sub_service_active 
    ON service_addons (sub_service_id, is_active);

-- Accelerates customer app home screen banner queries
CREATE INDEX IF NOT EXISTS idx_banners_active 
    ON banners (is_active)
    WHERE is_active = true;

-- Accelerates active promo coupons lookup
CREATE INDEX IF NOT EXISTS idx_coupons_active_validity 
    ON coupons (is_active, valid_from, valid_until)
    WHERE is_active = true;


-- --------------------------------------------------------------------
-- 8. USERS & PAYMENTS
-- --------------------------------------------------------------------
-- Accelerates admin user list & dashboard counting: countByRole / findByRole
CREATE INDEX IF NOT EXISTS idx_users_role 
    ON users (role);

-- Accelerates financial auditing & payment status filtering
CREATE INDEX IF NOT EXISTS idx_payments_status 
    ON payments (status);
