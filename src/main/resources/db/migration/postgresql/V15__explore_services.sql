-- ====================================================================
-- HomeEase PostgreSQL Migration V15: Explore Services System
-- ====================================================================

CREATE TABLE IF NOT EXISTS explore_services (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(150) NOT NULL,
    slug           VARCHAR(160) NOT NULL UNIQUE,
    description    TEXT,
    category_id    UUID REFERENCES services(service_id) ON DELETE SET NULL,
    image_url      TEXT NOT NULL,
    price          NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    original_price NUMERIC(10, 2) CHECK (original_price IS NULL OR original_price >= price),
    rating         NUMERIC(2, 1) NOT NULL DEFAULT 4.8 CHECK (rating >= 0 AND rating <= 5.0),
    review_count   INTEGER NOT NULL DEFAULT 0 CHECK (review_count >= 0),
    display_order  INTEGER NOT NULL DEFAULT 0,
    is_active      BOOLEAN NOT NULL DEFAULT true,
    is_featured    BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ
);

-- Partial index for active customer explore services
CREATE INDEX IF NOT EXISTS idx_explore_active_order 
    ON explore_services (display_order ASC, created_at DESC) 
    WHERE is_active = true AND deleted_at IS NULL;

-- Index for category lookup
CREATE INDEX IF NOT EXISTS idx_explore_category 
    ON explore_services (category_id);

-- Index for slug lookup
CREATE INDEX IF NOT EXISTS idx_explore_slug 
    ON explore_services (slug);

-- Seed Initial Explore Services using dynamic category lookup (fail-safe for any DB state)
INSERT INTO explore_services (
    id, name, slug, description, category_id, image_url, price, original_price, rating, review_count, display_order, is_active, is_featured
) VALUES
(
    'a1b2c3d4-0001-4000-8000-000000000001',
    'Full Home Deep Cleaning',
    'full-home-deep-cleaning',
    'Complete top-to-bottom sanitization with mechanized scrubbers and eco-safe disinfectants.',
    (SELECT service_id FROM services WHERE name ILIKE '%Cleaning%' LIMIT 1),
    'https://images.unsplash.com/photo-1581578731548-c64695cc6952?w=800&q=80',
    1499.00,
    1999.00,
    4.9,
    142,
    1,
    true,
    true
),
(
    'a1b2c3d4-0002-4000-8000-000000000002',
    'Kitchen Intensive Degreasing & Sanitization',
    'kitchen-intensive-degreasing',
    'Deep chimney, stovetop, slab and tile oil stain removal using hot foam and industrial degreasers.',
    (SELECT service_id FROM services WHERE name ILIKE '%Cleaning%' LIMIT 1),
    'https://images.unsplash.com/photo-1556911220-e15b29be8c8f?w=800&q=80',
    799.00,
    999.00,
    4.8,
    89,
    2,
    true,
    true
),
(
    'a1b2c3d4-0003-4000-8000-000000000003',
    'Switchboard Repair & Socket Replacement',
    'switchboard-repair-socket-replacement',
    'Safe diagnostics, burnt wire insulation rewiring, and modular switch replacements.',
    (SELECT service_id FROM services WHERE name ILIKE '%Electrician%' LIMIT 1),
    'https://images.unsplash.com/photo-1621905251189-08b45d6a269e?w=800&q=80',
    149.00,
    249.00,
    4.7,
    64,
    3,
    true,
    false
),
(
    'a1b2c3d4-0004-4000-8000-000000000004',
    'Bathroom Deep Descale & Sanitization',
    'bathroom-deep-descale-sanitization',
    'Hard-water stain scrubbing, tap acid wash, mirror polish and drain unclog treatment.',
    (SELECT service_id FROM services WHERE name ILIKE '%Cleaning%' LIMIT 1),
    'https://images.unsplash.com/photo-1584622650111-993a426fbf0a?w=800&q=80',
    499.00,
    699.00,
    4.9,
    115,
    4,
    true,
    false
)
ON CONFLICT (id) DO NOTHING;
