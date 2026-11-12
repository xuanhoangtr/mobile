-- DRAFT initial schema for CHAT module (D02 proposal).
-- Requires `users(id)` (AUTH) and `media_objects(id)` (MEDIA) tables to be created
-- or aligned before applying foreign keys across modules.

CREATE TABLE IF NOT EXISTS conversations (
    id BIGSERIAL PRIMARY KEY,
    type VARCHAR(16) NOT NULL CHECK (type IN ('DIRECT', 'GROUP')),
    name VARCHAR(120),
    avatar_url TEXT,
    direct_pair_key VARCHAR(64) UNIQUE,
    created_by BIGINT NOT NULL,
    last_message_id BIGINT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS conversation_members (
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LEFT', 'REMOVED')),
    is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
    pinned_at TIMESTAMPTZ,
    last_read_message_id BIGINT,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at TIMESTAMPTZ,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    client_message_id UUID NOT NULL,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id BIGINT NOT NULL,
    type VARCHAR(24) NOT NULL,
    content TEXT,
    media_ref BIGINT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    reply_to_id BIGINT REFERENCES messages(id) ON DELETE SET NULL,
    forwarded_from_id BIGINT REFERENCES messages(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    UNIQUE (sender_id, client_message_id)
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation_id_id
    ON messages (conversation_id, id DESC);

CREATE TABLE IF NOT EXISTS message_receipts (
    message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    recipient_id BIGINT NOT NULL,
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    PRIMARY KEY (message_id, recipient_id)
);

CREATE TABLE IF NOT EXISTS message_reactions (
    message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    emoji VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id, emoji)
);

CREATE TABLE IF NOT EXISTS pinned_messages (
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    pinned_by BIGINT NOT NULL,
    pinned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (conversation_id, message_id)
);
