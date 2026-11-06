-- ====================================================================
-- HomeEase PostgreSQL Migration V16: Dynamic Service Detail & Cart System
-- ====================================================================

-- 1. Extend services table with dynamic metadata & policies
ALTER TABLE services
    ADD COLUMN IF NOT EXISTS subtitle VARCHAR(255),
    ADD COLUMN IF NOT EXISTS banner_image TEXT,
    ADD COLUMN IF NOT EXISTS rating NUMERIC(2, 1) DEFAULT 4.8 CHECK (rating >= 0 AND rating <= 5.0),
    ADD COLUMN IF NOT EXISTS total_reviews INTEGER DEFAULT 0 CHECK (total_reviews >= 0),
    ADD COLUMN IF NOT EXISTS starting_price NUMERIC(10, 2) DEFAULT 99.00 CHECK (starting_price >= 0),
    ADD COLUMN IF NOT EXISTS cancellation_policy TEXT,
    ADD COLUMN IF NOT EXISTS booking_types VARCHAR(50) DEFAULT 'INSTANT,SCHEDULED';

-- 2. Service Durations (e.g. 30 mins, 60 mins, 90 mins, 120 mins)
CREATE TABLE IF NOT EXISTS service_durations (
    duration_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id      UUID NOT NULL REFERENCES services(service_id) ON DELETE CASCADE,
    minutes         INTEGER NOT NULL CHECK (minutes > 0),
    label           VARCHAR(100),
    price           NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    original_price  NUMERIC(10, 2) CHECK (original_price IS NULL OR original_price >= price),
    is_popular      BOOLEAN NOT NULL DEFAULT false,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    display_order   INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_service_durations_service
    ON service_durations(service_id, is_active, display_order);

-- 3. Service Packages (e.g. Deep Villa Cleaning, Kitchen Combo)
CREATE TABLE IF NOT EXISTS service_packages (
    package_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id      UUID NOT NULL REFERENCES services(service_id) ON DELETE CASCADE,
    name            VARCHAR(150) NOT NULL,
    description     TEXT,
    price           NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    original_price  NUMERIC(10, 2) CHECK (original_price IS NULL OR original_price >= price),
    duration_mins   INTEGER NOT NULL DEFAULT 60 CHECK (duration_mins > 0),
    inclusions      JSONB DEFAULT '[]'::jsonb,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    display_order   INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_service_packages_service
    ON service_packages(service_id, is_active, display_order);

-- 4. Service Features & What's Included / Excluded
CREATE TABLE IF NOT EXISTS service_features (
    feature_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id      UUID NOT NULL REFERENCES services(service_id) ON DELETE CASCADE,
    title           VARCHAR(150) NOT NULL,
    description     TEXT,
    icon_url        TEXT,
    is_included     BOOLEAN NOT NULL DEFAULT true,
    display_order   INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_service_features_service
    ON service_features(service_id, display_order);

-- 5. Service How It Works Steps (Timeline)
CREATE TABLE IF NOT EXISTS service_how_it_works (
    step_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id      UUID NOT NULL REFERENCES services(service_id) ON DELETE CASCADE,
    step_number     INTEGER NOT NULL DEFAULT 1,
    title           VARCHAR(150) NOT NULL,
    description     TEXT,
    icon_url        TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_service_how_it_works_service
    ON service_how_it_works(service_id, step_number);

-- 6. Service Instructions & Things to Know
CREATE TABLE IF NOT EXISTS service_instructions (
    instruction_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id      UUID NOT NULL REFERENCES services(service_id) ON DELETE CASCADE,
    title           VARCHAR(150) NOT NULL,
    description     TEXT,
    display_order   INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_service_instructions_service
    ON service_instructions(service_id, display_order);

-- 7. Service Gallery & Banner Images
CREATE TABLE IF NOT EXISTS service_images (
    image_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id      UUID NOT NULL REFERENCES services(service_id) ON DELETE CASCADE,
    image_url       TEXT NOT NULL,
    caption         VARCHAR(150),
    is_banner       BOOLEAN NOT NULL DEFAULT false,
    display_order   INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_service_images_service
    ON service_images(service_id, display_order);

-- 8. Customer Booking Carts
CREATE TABLE IF NOT EXISTS booking_carts (
    cart_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL UNIQUE REFERENCES users(user_id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_booking_carts_user
    ON booking_carts(user_id);

-- 9. Booking Cart Items
CREATE TABLE IF NOT EXISTS booking_cart_items (
    cart_item_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id         UUID NOT NULL REFERENCES booking_carts(cart_id) ON DELETE CASCADE,
    service_id      UUID NOT NULL REFERENCES services(service_id) ON DELETE CASCADE,
    duration_id     UUID REFERENCES service_durations(duration_id) ON DELETE SET NULL,
    package_id      UUID REFERENCES service_packages(package_id) ON DELETE SET NULL,
    sub_service_id  UUID REFERENCES sub_services(sub_service_id) ON DELETE SET NULL,
    quantity        INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
    unit_price      NUMERIC(10, 2) NOT NULL CHECK (unit_price >= 0),
    original_price  NUMERIC(10, 2),
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_booking_cart_items_cart
    ON booking_cart_items(cart_id);
CREATE INDEX IF NOT EXISTS idx_booking_cart_items_service
    ON booking_cart_items(service_id);

-- 10. Booking Status History (Audit trail of transitions)
CREATE TABLE IF NOT EXISTS booking_status_history (
    history_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id      UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    from_status     VARCHAR(50),
    to_status       VARCHAR(50) NOT NULL,
    changed_by      VARCHAR(50),
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_booking_status_history_booking
    ON booking_status_history(booking_id, created_at DESC);

-- 11. Booking Pricing Details (Immutable Snapshot)
CREATE TABLE IF NOT EXISTS booking_pricing_details (
    pricing_detail_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id              UUID NOT NULL UNIQUE REFERENCES bookings(booking_id) ON DELETE CASCADE,
    base_service_price      NUMERIC(10, 2) NOT NULL CHECK (base_service_price >= 0),
    additional_charges      NUMERIC(10, 2) NOT NULL DEFAULT 0.00 CHECK (additional_charges >= 0),
    tax_amount              NUMERIC(10, 2) NOT NULL DEFAULT 0.00 CHECK (tax_amount >= 0),
    discount_amount         NUMERIC(10, 2) NOT NULL DEFAULT 0.00 CHECK (discount_amount >= 0),
    coupon_code             VARCHAR(50),
    final_payable_amount    NUMERIC(10, 2) NOT NULL CHECK (final_payable_amount >= 0),
    price_breakdown_json    JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_booking_pricing_details_booking
    ON booking_pricing_details(booking_id);

-- ====================================================================
-- SEED DYNAMIC METADATA FOR EXISTING SERVICES
-- ====================================================================

-- Update Cleaning Service Metadata
UPDATE services SET
    subtitle = 'Mechanized floor scrubbing, sanitization & chemical treatments',
    banner_image = 'https://images.unsplash.com/photo-1581578731548-c64695cc6952?w=1200&q=80',
    rating = 4.9,
    total_reviews = 1420,
    starting_price = 499.00,
    cancellation_policy = 'Free cancellation up to 2 hours before scheduled start. Cancellations within 2 hours incur a nominal ₹50 partner inconvenience fee.',
    booking_types = 'INSTANT,SCHEDULED'
WHERE name ILIKE '%Cleaning%';

-- Insert Cleaning Durations
INSERT INTO service_durations (service_id, minutes, label, price, original_price, is_popular, is_active, display_order)
SELECT s.service_id, d.minutes, d.label, d.price, d.original_price, d.is_popular, true, d.display_order
FROM services s
CROSS JOIN (VALUES
    (30, '30 Mins Quick Wash', 299.00, 399.00, false, 1),
    (60, '1 Hour Deep Refresh', 499.00, 699.00, false, 2),
    (120, '2 Hours Intensive Spa', 899.00, 1199.00, true, 3),
    (180, '3 Hours Complete House', 1299.00, 1799.00, false, 4)
) AS d(minutes, label, price, original_price, is_popular, display_order)
WHERE s.name ILIKE '%Cleaning%'
ON CONFLICT DO NOTHING;

-- Insert Cleaning How It Works
INSERT INTO service_how_it_works (service_id, step_number, title, description)
SELECT s.service_id, h.step_number, h.title, h.description
FROM services s
CROSS JOIN (VALUES
    (1, 'Select Duration & Add to Cart', 'Choose your required service duration or customized package.'),
    (2, 'Background Verified Pro Arrives', 'Equipped with industrial scrubbers and hospital-grade sanitizers.'),
    (3, 'Share 4-Digit Security PIN', 'Provide OTP to partner to officially start the recorded job session.'),
    (4, 'Inspect & Digital Pay', 'Review the sparkling results and settle securely with zero convenience fee.')
) AS h(step_number, title, description)
WHERE s.name ILIKE '%Cleaning%'
ON CONFLICT DO NOTHING;

-- Insert Cleaning Features & Inclusions
INSERT INTO service_features (service_id, title, description, is_included, display_order)
SELECT s.service_id, f.title, f.description, f.is_included, f.display_order
FROM services s
CROSS JOIN (VALUES
    ('Industrial Machine Scrubbing', 'Mechanized high-speed single disc floor cleaning', true, 1),
    ('Eco-Safe Disinfectants', 'Pet-friendly and baby-safe chemical sanitization', true, 2),
    ('Steam Extraction & Degreasing', 'Thermal degreasing for tiles, kitchen counters & hobs', true, 3),
    ('Painting & Wallpaper Fix', 'Major masonry and civil repairs not included', false, 4)
) AS f(title, description, is_included, display_order)
WHERE s.name ILIKE '%Cleaning%'
ON CONFLICT DO NOTHING;

-- Insert Cleaning Instructions
INSERT INTO service_instructions (service_id, title, description, display_order)
SELECT s.service_id, i.title, i.description, i.display_order
FROM services s
CROSS JOIN (VALUES
    ('Electricity & Water Supply', 'Please ensure continuous water and electric power access for machines.', 1),
    ('Valuables & Delicate Items', 'Please lock away high-value jewelry and delicate ceramics prior to arrival.', 2)
) AS i(title, description, display_order)
WHERE s.name ILIKE '%Cleaning%'
ON CONFLICT DO NOTHING;

-- Update Electrician Services Metadata
UPDATE services SET
    subtitle = 'Certified electricians for wiring, appliance setup & repairs',
    banner_image = 'https://images.unsplash.com/photo-1621905251189-08b45d6a269e?w=1200&q=80',
    rating = 4.8,
    total_reviews = 890,
    starting_price = 149.00,
    cancellation_policy = 'Free cancellation up to 30 mins before dispatch.',
    booking_types = 'INSTANT,SCHEDULED'
WHERE name ILIKE '%Electrician%';

-- Insert Electrician Durations
INSERT INTO service_durations (service_id, minutes, label, price, original_price, is_popular, is_active, display_order)
SELECT s.service_id, d.minutes, d.label, d.price, d.original_price, d.is_popular, true, d.display_order
FROM services s
CROSS JOIN (VALUES
    (30, '30 Mins Quick Inspection', 149.00, 199.00, false, 1),
    (60, '1 Hour Repair & Wiring', 249.00, 349.00, true, 2),
    (120, '2 Hours Heavy Appliance Setup', 449.00, 599.00, false, 3)
) AS d(minutes, label, price, original_price, is_popular, display_order)
WHERE s.name ILIKE '%Electrician%'
ON CONFLICT DO NOTHING;

