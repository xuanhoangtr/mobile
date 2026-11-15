-- Add last_seen_at column for realtime user presence (#8).
ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ;
