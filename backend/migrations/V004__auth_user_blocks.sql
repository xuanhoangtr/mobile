-- AUTH user-block relationships. Only the initiating user can remove their block.
CREATE TABLE IF NOT EXISTS user_blocks (
    blocker_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (blocker_user_id, blocked_user_id),
    CONSTRAINT user_blocks_distinct_users CHECK (blocker_user_id <> blocked_user_id)
);

-- Supports reverse-direction privacy checks when a target has blocked the requester.
CREATE INDEX IF NOT EXISTS idx_user_blocks_blocked_user
    ON user_blocks (blocked_user_id, blocker_user_id);
