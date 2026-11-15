-- Schema for MEDIA module: media_objects, device_push_tokens, call_sessions, and message_hidden.

CREATE TABLE IF NOT EXISTS media_objects (
    id BIGINT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    conversation_id BIGINT,
    file_name TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    byte_size BIGINT NOT NULL,
    upload_state TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    access_scope TEXT NOT NULL,
    storage_key TEXT
);

ALTER TABLE IF EXISTS media_objects
    ADD COLUMN IF NOT EXISTS storage_key TEXT;

CREATE INDEX IF NOT EXISTS idx_media_objects_owner_id
    ON media_objects (owner_id);

CREATE INDEX IF NOT EXISTS idx_media_objects_conversation_id
    ON media_objects (conversation_id)
    WHERE conversation_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS device_push_tokens (
    user_id BIGINT NOT NULL,
    device_id TEXT NOT NULL,
    token TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, device_id)
);

CREATE TABLE IF NOT EXISTS call_sessions (
    call_id TEXT PRIMARY KEY,
    conversation_id BIGINT,
    caller_id BIGINT NOT NULL,
    callee_id BIGINT,
    media TEXT NOT NULL,
    mode TEXT NOT NULL DEFAULT 'DIRECT',
    state TEXT NOT NULL,
    participant_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    active_participant_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE IF EXISTS call_sessions
    ADD COLUMN IF NOT EXISTS active_participant_ids JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_call_sessions_conversation_id
    ON call_sessions (conversation_id)
    WHERE conversation_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS message_hidden (
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    hidden_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_message_hidden_user_id
    ON message_hidden (user_id, message_id);
