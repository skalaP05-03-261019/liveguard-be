-- Additive: legacy messages keep live_id NULL; no invented historic broadcast.
CREATE TABLE platform.lives (
	id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	channel_id uuid NOT NULL REFERENCES platform.channels (id),
	started_at timestamptz NOT NULL,
	ended_at timestamptz,
	CHECK (ended_at IS NULL OR ended_at >= started_at),
	UNIQUE (id, channel_id)
);

CREATE UNIQUE INDEX lives_one_active_per_channel_idx
	ON platform.lives (channel_id) WHERE ended_at IS NULL;

ALTER TABLE platform.messages
	ADD COLUMN live_id uuid,
	ADD CONSTRAINT messages_live_channel_fk
		FOREIGN KEY (live_id, channel_id) REFERENCES platform.lives (id, channel_id);

CREATE INDEX messages_live_sent_at_idx
	ON platform.messages (live_id, sent_at DESC);
CREATE INDEX messages_live_flood_idx
	ON platform.messages (live_id, author_id, normalized_text, sent_at);

CREATE TABLE platform.message_hold_reasons (
	message_id uuid NOT NULL REFERENCES platform.messages (id),
	case_id uuid NOT NULL,
	reason text NOT NULL,
	created_at timestamptz NOT NULL,
	released_at timestamptz,
	PRIMARY KEY (message_id, case_id)
);
