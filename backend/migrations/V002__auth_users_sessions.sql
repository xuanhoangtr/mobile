-- AUTH schema follows the CHAT V001 migration without modifying that draft.
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    username VARCHAR(24) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    normalized_phone VARCHAR(16),
    phone_discoverable BOOLEAN NOT NULL DEFAULT FALSE,
    avatar_url TEXT,
    bio VARCHAR(160),
    privacy_settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT users_email_normalized CHECK (email = LOWER(BTRIM(email))),
    CONSTRAINT users_username_normalized CHECK (username = LOWER(username)),
    CONSTRAINT users_username_format CHECK (username ~ '^[a-z0-9_]{3,24}$'),
    CONSTRAINT users_phone_e164 CHECK (normalized_phone IS NULL OR normalized_phone ~ '^\+[1-9][0-9]{7,14}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email ON users (email);
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username ON users (username);
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_normalized_phone
    ON users (normalized_phone) WHERE normalized_phone IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_users_active_search
    ON users (id) WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS sessions (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash CHAR(64) UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    access_token_expires_at TIMESTAMPTZ NOT NULL,
    refresh_token_expires_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_reason VARCHAR(64),
    device_name VARCHAR(120),
    client_platform VARCHAR(32),
    CONSTRAINT sessions_expiry_order CHECK (refresh_token_expires_at >= access_token_expires_at),
    CONSTRAINT sessions_revocation_clears_token CHECK (
        (revoked_at IS NULL AND refresh_token_hash IS NOT NULL)
        OR (revoked_at IS NOT NULL AND refresh_token_hash IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_active
    ON sessions (user_id, refresh_token_expires_at DESC)
    WHERE revoked_at IS NULL;
