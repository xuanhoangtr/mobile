-- AUTH phone discovery and friend relationships; V001 and V002 remain immutable.
-- A phone is verified only when the selected OTP provider records phone_verified_at.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS phone_verified_at TIMESTAMPTZ;

-- Existing discovery preferences do not prove provider-backed verification.
UPDATE users
SET phone_discoverable = FALSE
WHERE phone_verified_at IS NULL AND phone_discoverable = TRUE;

CREATE TABLE IF NOT EXISTS friend_requests (
    id BIGSERIAL PRIMARY KEY,
    sender_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    responded_at TIMESTAMPTZ,
    CONSTRAINT friend_requests_distinct_users CHECK (sender_user_id <> recipient_user_id),
    CONSTRAINT friend_requests_response_time CHECK (
        (status = 'PENDING' AND responded_at IS NULL)
        OR (status <> 'PENDING' AND responded_at IS NOT NULL)
    )
);

-- A pair may have only one open request, regardless of which side initiated it.
CREATE UNIQUE INDEX IF NOT EXISTS uq_friend_requests_pending_pair
    ON friend_requests (
        LEAST(sender_user_id, recipient_user_id),
        GREATEST(sender_user_id, recipient_user_id)
    ) WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_friend_requests_incoming
    ON friend_requests (recipient_user_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_friend_requests_outgoing
    ON friend_requests (sender_user_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS friend_relationships (
    user_low_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_high_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_low_id, user_high_id),
    CONSTRAINT friend_relationships_ordered_pair CHECK (user_low_id < user_high_id)
);

CREATE INDEX IF NOT EXISTS idx_friend_relationships_high_user
    ON friend_relationships (user_high_id, user_low_id);
