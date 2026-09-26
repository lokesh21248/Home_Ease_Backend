CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "postgis";

-- Custom Enums
CREATE TYPE user_role AS ENUM ('CUSTOMER', 'WORKER', 'ADMIN');
CREATE TYPE pricing_model AS ENUM ('FIXED', 'HOURLY', 'PER_UNIT');
CREATE TYPE booking_stage AS ENUM ('SEARCHING', 'ACCEPTED', 'ARRIVED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'WORKER_NOT_FOUND');
CREATE TYPE payment_mode AS ENUM ('COD', 'UPI', 'CARD', 'NET_BANKING');
CREATE TYPE transaction_state AS ENUM ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED');
CREATE TYPE discount_model AS ENUM ('PERCENTAGE', 'FIXED_AMOUNT');
CREATE TYPE notify_status AS ENUM ('PENDING', 'SENT', 'FAILED', 'CANCELLED');

-- 1. users
CREATE TABLE users (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20) UNIQUE NOT NULL,
    email VARCHAR(150) UNIQUE,
    role user_role NOT NULL DEFAULT 'CUSTOMER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. workers
CREATE TABLE workers (
    worker_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID UNIQUE NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    address TEXT NOT NULL,
    pan_number VARCHAR(20) NOT NULL,
    pan_doc_url TEXT NOT NULL,
    aadhaar_doc_url TEXT NOT NULL,
    bank_account_no VARCHAR(30) NOT NULL,
    bank_ifsc VARCHAR(20) NOT NULL,
    is_online BOOLEAN NOT NULL DEFAULT false,
    current_lat DOUBLE PRECISION,
    current_lng DOUBLE PRECISION,
    location GEOGRAPHY(Point, 4326),
    blocked_until TIMESTAMPTZ,
    is_verified BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_workers_spatial ON workers USING GIST(location);
CREATE INDEX idx_workers_dispatch ON workers(is_online, is_verified, blocked_until);

-- 3. services
CREATE TABLE services (
    service_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    image_url TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 4. sub_services
CREATE TABLE sub_services (
    sub_service_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id UUID NOT NULL REFERENCES services(service_id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    pricing_type pricing_model NOT NULL DEFAULT 'FIXED',
    base_price NUMERIC(10,2) NOT NULL,
    unit_label VARCHAR(30),
    estimated_mins INTEGER NOT NULL,
    image_url TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 5. service_addons
CREATE TABLE service_addons (
    addon_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sub_service_id UUID NOT NULL REFERENCES sub_services(sub_service_id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    price NUMERIC(10,2) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 6. worker_services
CREATE TABLE worker_services (
    worker_id UUID NOT NULL REFERENCES workers(worker_id) ON DELETE CASCADE,
    sub_service_id UUID NOT NULL REFERENCES sub_services(sub_service_id) ON DELETE CASCADE,
    PRIMARY KEY (worker_id, sub_service_id)
);

-- 7. coupons
CREATE TABLE coupons (
    coupon_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) UNIQUE NOT NULL,
    discount_type discount_model NOT NULL,
    discount_val NUMERIC(10,2) NOT NULL,
    min_order_value NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    max_discount_amount NUMERIC(10,2),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    usage_limit INTEGER,
    times_used INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 8. bookings
CREATE TABLE bookings (
    booking_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(user_id),
    worker_id UUID REFERENCES workers(worker_id),
    service_id UUID NOT NULL REFERENCES services(service_id),
    coupon_id UUID REFERENCES coupons(coupon_id),
    status booking_stage NOT NULL DEFAULT 'SEARCHING',
    pin_code CHAR(4) NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    user_lat DOUBLE PRECISION NOT NULL,
    user_lng DOUBLE PRECISION NOT NULL,
    user_location GEOGRAPHY(Point, 4326) NOT NULL,
    base_amount NUMERIC(10,2) NOT NULL,
    extra_amount NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    discount_amount NUMERIC(10,2) NOT NULL DEFAULT 0.00,
    total_amount NUMERIC(10,2) NOT NULL,
    payment_status transaction_state NOT NULL DEFAULT 'PENDING',
    payment_method payment_mode NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_bookings_spatial ON bookings USING GIST(user_location);
CREATE INDEX idx_bookings_status ON bookings(status);

-- 9. booking_services
CREATE TABLE booking_services (
    booking_service_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    sub_service_id UUID NOT NULL REFERENCES sub_services(sub_service_id),
    quantity INTEGER NOT NULL DEFAULT 1,
    unit_price NUMERIC(10,2) NOT NULL,
    is_additional BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 10. booking_addons
CREATE TABLE booking_addons (
    booking_addon_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    addon_id UUID NOT NULL REFERENCES service_addons(addon_id),
    quantity INTEGER NOT NULL DEFAULT 1,
    unit_price NUMERIC(10,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 11. assignment_attempts
CREATE TABLE assignment_attempts (
    attempt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    worker_id UUID NOT NULL REFERENCES workers(worker_id),
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at TIMESTAMPTZ,
    accepted BOOLEAN
);

-- 12. payments
CREATE TABLE payments (
    payment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID UNIQUE NOT NULL REFERENCES bookings(booking_id) ON DELETE RESTRICT,
    transaction_ref VARCHAR(100),
    gross_amount NUMERIC(10,2) NOT NULL,
    platform_commission_percent NUMERIC(5,2) NOT NULL DEFAULT 2.00,
    platform_commission_amount NUMERIC(10,2) NOT NULL,
    worker_payout_amount NUMERIC(10,2) NOT NULL,
    status transaction_state NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 13. reviews
CREATE TABLE reviews (
    review_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL REFERENCES bookings(booking_id),
    user_id UUID NOT NULL REFERENCES users(user_id),
    worker_id UUID NOT NULL REFERENCES workers(worker_id),
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 14. banners
CREATE TABLE banners (
    banner_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title VARCHAR(100) NOT NULL,
    image_url TEXT NOT NULL,
    target_type VARCHAR(50),
    target_id UUID,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 15. notifications
CREATE TABLE notifications (
    notification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(user_id),
    worker_id UUID REFERENCES workers(worker_id),
    title VARCHAR(150) NOT NULL,
    message TEXT NOT NULL,
    type VARCHAR(50) NOT NULL,
    scheduled_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    status notify_status NOT NULL DEFAULT 'PENDING',
    is_read BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_notifications_queue ON notifications(status, scheduled_at);
