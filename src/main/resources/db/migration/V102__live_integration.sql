-- Additive: legacy messages keep live_id NULL; no invented historic broadcast.
CREATE TABLE platform.lives (
 id uuid PRIMARY KEY, channel_id uuid NOT NULL REFERENCES platform.channels(id),
 started_at timestamptz NOT NULL, ended_at timestamptz,
 CHECK (ended_at IS NULL OR ended_at >= started_at));
CREATE UNIQUE INDEX one_active_live_per_channel ON platform.lives(channel_id) WHERE ended_at IS NULL;
ALTER TABLE platform.messages ADD COLUMN live_id uuid REFERENCES platform.lives(id);
CREATE INDEX messages_live_time ON platform.messages(live_id, sent_at, id);
CREATE TABLE platform.message_hold_reasons (
 message_id uuid NOT NULL REFERENCES platform.messages(id), case_id uuid NOT NULL,
 reason text NOT NULL, created_at timestamptz NOT NULL, released_at timestamptz,
 PRIMARY KEY(message_id,case_id));
