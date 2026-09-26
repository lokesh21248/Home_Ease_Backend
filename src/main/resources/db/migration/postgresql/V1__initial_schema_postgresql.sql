-- ==============================================
-- HomeEase PostgreSQL Schema (Supabase)
-- ==============================================

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. users
CREATE TABLE IF NOT EXISTS users (
    user_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    full_name VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20) NOT NULL UNIQUE,
    email VARCHAR(150) UNIQUE,
    role VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER' CHECK (role IN ('CUSTOMER', 'WORKER', 'ADMIN')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. workers
CREATE TABLE IF NOT EXISTS workers (
    worker_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE,
    address TEXT NOT NULL,
    pan_number VARCHAR(20) NOT NULL,
    pan_doc_url TEXT NOT NULL,
    aadhaar_doc_url TEXT NOT NULL,
    bank_account_no VARCHAR(30) NOT NULL,
    bank_ifsc VARCHAR(20) NOT NULL,
    is_online BOOLEAN NOT NULL DEFAULT false,
    current_lat DOUBLE PRECISION,
    current_lng DOUBLE PRECISION,
    blocked_until TIMESTAMP NULL,
    is_verified BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_workers_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_workers_dispatch ON workers(is_online, is_verified, blocked_until);

-- 3. services
CREATE TABLE IF NOT EXISTS services (
    service_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    image_url TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 4. sub_services
CREATE TABLE IF NOT EXISTS sub_services (
    sub_service_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    service_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    pricing_type VARCHAR(20) NOT NULL DEFAULT 'FIXED' CHECK (pricing_type IN ('FIXED', 'HOURLY', 'PER_UNIT')),
    base_price DECIMAL(10,2) NOT NULL,
    unit_label VARCHAR(30),
    estimated_mins INTEGER NOT NULL,
    image_url TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sub_services_service FOREIGN KEY (service_id) REFERENCES services(service_id) ON DELETE CASCADE
);

-- 5. service_addons
CREATE TABLE IF NOT EXISTS service_addons (
    addon_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    sub_service_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_addons_sub_service FOREIGN KEY (sub_service_id) REFERENCES sub_services(sub_service_id) ON DELETE CASCADE
);

-- 6. worker_services
CREATE TABLE IF NOT EXISTS worker_services (
    worker_id UUID NOT NULL,
    sub_service_id UUID NOT NULL,
    PRIMARY KEY (worker_id, sub_service_id),
    CONSTRAINT fk_ws_worker FOREIGN KEY (worker_id) REFERENCES workers(worker_id) ON DELETE CASCADE,
    CONSTRAINT fk_ws_sub_service FOREIGN KEY (sub_service_id) REFERENCES sub_services(sub_service_id) ON DELETE CASCADE
);

-- 7. coupons
CREATE TABLE IF NOT EXISTS coupons (
    coupon_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code VARCHAR(50) NOT NULL UNIQUE,
    discount_type VARCHAR(20) NOT NULL CHECK (discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    discount_val DECIMAL(10,2) NOT NULL,
    min_order_value DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    max_discount_amount DECIMAL(10,2),
    valid_from TIMESTAMP NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    usage_limit INTEGER,
    times_used INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 8. bookings
CREATE TABLE IF NOT EXISTS bookings (
    booking_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    worker_id UUID,
    service_id UUID NOT NULL,
    coupon_id UUID,
    status VARCHAR(30) NOT NULL DEFAULT 'SEARCHING' CHECK (status IN ('SEARCHING', 'ACCEPTED', 'ARRIVED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'WORKER_NOT_FOUND')),
    pin_code CHAR(4) NOT NULL,
    scheduled_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    user_lat DOUBLE PRECISION NOT NULL,
    user_lng DOUBLE PRECISION NOT NULL,
    base_amount DECIMAL(10,2) NOT NULL,
    extra_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    total_amount DECIMAL(10,2) NOT NULL,
    payment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (payment_status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED')),
    payment_method VARCHAR(20) NOT NULL CHECK (payment_method IN ('COD', 'UPI', 'CARD', 'NET_BANKING')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT fk_bookings_worker FOREIGN KEY (worker_id) REFERENCES workers(worker_id),
    CONSTRAINT fk_bookings_service FOREIGN KEY (service_id) REFERENCES services(service_id),
    CONSTRAINT fk_bookings_coupon FOREIGN KEY (coupon_id) REFERENCES coupons(coupon_id)
);
CREATE INDEX IF NOT EXISTS idx_bookings_status ON bookings(status);

-- 9. booking_services
CREATE TABLE IF NOT EXISTS booking_services (
    booking_service_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID NOT NULL,
    sub_service_id UUID NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    unit_price DECIMAL(10,2) NOT NULL,
    is_additional BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bs_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_bs_sub_service FOREIGN KEY (sub_service_id) REFERENCES sub_services(sub_service_id)
);

-- 10. booking_addons
CREATE TABLE IF NOT EXISTS booking_addons (
    booking_addon_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID NOT NULL,
    addon_id UUID NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    unit_price DECIMAL(10,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ba_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_ba_addon FOREIGN KEY (addon_id) REFERENCES service_addons(addon_id)
);

-- 11. assignment_attempts
CREATE TABLE IF NOT EXISTS assignment_attempts (
    attempt_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID NOT NULL,
    worker_id UUID NOT NULL,
    attempted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at TIMESTAMP NULL,
    accepted BOOLEAN,
    CONSTRAINT fk_aa_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_aa_worker FOREIGN KEY (worker_id) REFERENCES workers(worker_id)
);

-- 12. payments
CREATE TABLE IF NOT EXISTS payments (
    payment_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID NOT NULL UNIQUE,
    transaction_ref VARCHAR(100),
    gross_amount DECIMAL(10,2) NOT NULL,
    platform_commission_percent DECIMAL(5,2) NOT NULL DEFAULT 2.00,
    platform_commission_amount DECIMAL(10,2) NOT NULL,
    worker_payout_amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payments_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE RESTRICT
);

-- 13. reviews
CREATE TABLE IF NOT EXISTS reviews (
    review_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID NOT NULL,
    user_id UUID NOT NULL,
    worker_id UUID NOT NULL,
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reviews_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id),
    CONSTRAINT fk_reviews_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT fk_reviews_worker FOREIGN KEY (worker_id) REFERENCES workers(worker_id)
);

-- 14. banners
CREATE TABLE IF NOT EXISTS banners (
    banner_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(100) NOT NULL,
    image_url TEXT NOT NULL,
    target_type VARCHAR(50),
    target_id UUID,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 15. notifications
CREATE TABLE IF NOT EXISTS notifications (
    notification_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID,
    worker_id UUID,
    title VARCHAR(150) NOT NULL,
    message TEXT NOT NULL,
    type VARCHAR(50) NOT NULL,
    scheduled_at TIMESTAMP NULL,
    sent_at TIMESTAMP NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    is_read BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT fk_notifications_worker FOREIGN KEY (worker_id) REFERENCES workers(worker_id)
);
CREATE INDEX IF NOT EXISTS idx_notifications_queue ON notifications(status, scheduled_at);

-- ==============================================
-- Default Seed Data
-- ==============================================

-- Seed Admin User
INSERT INTO users (user_id, full_name, phone_number, email, role)
VALUES ('00000000-0000-0000-0000-000000000001', 'System Admin', '+910000000000', 'admin@homeease.com', 'ADMIN')
ON CONFLICT (user_id) DO NOTHING;

-- Seed Sample Services
INSERT INTO services (service_id, name, description, image_url, is_active)
VALUES 
('11111111-1111-1111-1111-111111111111', 'Electrician', 'Expert electrical repair and installation services', 'https://images.unsplash.com/photo-1621905251189-08b45d6a269e', true),
('22222222-2222-2222-2222-222222222222', 'Plumbing', 'Complete plumbing and sanitary fittings', 'https://images.unsplash.com/photo-1581092160607-ee22621dd758', true),
('33333333-3333-3333-3333-333333333333', 'Cleaning', 'Home deep cleaning and sanitization', 'https://images.unsplash.com/photo-1581578731548-c64695cc6952', true)
ON CONFLICT (service_id) DO NOTHING;

-- Seed Sample Sub-Services
INSERT INTO sub_services (sub_service_id, service_id, name, pricing_type, base_price, unit_label, estimated_mins, image_url, is_active)
VALUES
('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'Ceiling Fan Repair & Install', 'FIXED', 199.00, 'per fan', 30, 'https://images.unsplash.com/photo-1581092160607-ee22621dd758', true),
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '11111111-1111-1111-1111-111111111111', 'Switchboard Repair & Replacement', 'FIXED', 149.00, 'per switchboard', 20, 'https://images.unsplash.com/photo-1621905251189-08b45d6a269e', true),
('cccccccc-cccc-cccc-cccc-cccccccccccc', '22222222-2222-2222-2222-222222222222', 'Tap & Mixer Leak Repair', 'FIXED', 179.00, 'per tap', 25, 'https://images.unsplash.com/photo-1581092160607-ee22621dd758', true)
ON CONFLICT (sub_service_id) DO NOTHING;

-- Seed Sample Coupon
INSERT INTO coupons (coupon_id, code, discount_type, discount_val, min_order_value, max_discount_amount, valid_from, valid_until, usage_limit, is_active)
VALUES
('dddddddd-dddd-dddd-dddd-dddddddddddd', 'WELCOME50', 'FIXED_AMOUNT', 50.00, 299.00, 50.00, '2026-01-01 00:00:00', '2026-12-31 23:59:59', 1000, true)
ON CONFLICT (coupon_id) DO NOTHING;
