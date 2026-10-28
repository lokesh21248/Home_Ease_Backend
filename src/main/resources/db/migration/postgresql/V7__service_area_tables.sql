-- ====================================================================
-- HomeEase PostgreSQL Migration V7: Service Area Management Tables
-- Hierarchy: State → City → ServiceablePincode
-- is_active defaults to FALSE — records must be explicitly activated.
-- ====================================================================

-- 1. STATES
CREATE TABLE IF NOT EXISTS states (
    state_id     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(100) NOT NULL,
    name_norm    VARCHAR(100) NOT NULL,            -- UPPER(TRIM(name)) — enforces case-insensitive uniqueness
    is_active    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_states_name_norm UNIQUE (name_norm)
);

-- 2. CITIES
CREATE TABLE IF NOT EXISTS cities (
    city_id      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    state_id     UUID         NOT NULL REFERENCES states(state_id) ON DELETE RESTRICT,
    name         VARCHAR(100) NOT NULL,
    name_norm    VARCHAR(100) NOT NULL,            -- UPPER(TRIM(name))
    is_active    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cities_state_name UNIQUE (state_id, name_norm)
);

-- 3. SERVICEABLE PINCODES
CREATE TABLE IF NOT EXISTS serviceable_pincodes (
    pincode_id   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    pincode      VARCHAR(6)   NOT NULL,
    city_id      UUID         NOT NULL REFERENCES cities(city_id) ON DELETE RESTRICT,
    state_id     UUID         NOT NULL REFERENCES states(state_id) ON DELETE RESTRICT,
    area_name    VARCHAR(150),                     -- Optional human-readable area label
    is_active    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pincodes_pincode   UNIQUE (pincode),
    CONSTRAINT chk_pincode_format    CHECK  (pincode ~ '^[0-9]{6}$')
);

-- Indexes for fast lookup and admin filtering
CREATE INDEX IF NOT EXISTS idx_cities_state_id        ON cities(state_id);
CREATE INDEX IF NOT EXISTS idx_pincodes_city_id       ON serviceable_pincodes(city_id);
CREATE INDEX IF NOT EXISTS idx_pincodes_state_id      ON serviceable_pincodes(state_id);
CREATE INDEX IF NOT EXISTS idx_pincodes_pincode       ON serviceable_pincodes(pincode);
CREATE INDEX IF NOT EXISTS idx_pincodes_active        ON serviceable_pincodes(is_active) WHERE is_active = TRUE;
CREATE INDEX IF NOT EXISTS idx_states_active          ON states(is_active);
CREATE INDEX IF NOT EXISTS idx_cities_active          ON cities(is_active);
