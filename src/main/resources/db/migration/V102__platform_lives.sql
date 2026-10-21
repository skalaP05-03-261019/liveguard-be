-- 방송(E-1). ended_at이 NULL이면 방송 중이며, 채널당 진행 중 방송은 하나다.
CREATE TABLE platform.lives (
	id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
	channel_id uuid        NOT NULL REFERENCES platform.channels (id),
	started_at timestamptz NOT NULL,
	ended_at   timestamptz,
	CHECK (ended_at IS NULL OR ended_at >= started_at),
	-- messages의 (live_id, channel_id) 복합 FK 대상
	UNIQUE (id, channel_id)
);

CREATE UNIQUE INDEX lives_one_active_per_channel_idx
	ON platform.lives (channel_id) WHERE ended_at IS NULL;

-- 방송 도입 전 메시지는 live_id NULL로 둔다(A-17). 복합 FK라 메시지의 채널과 방송의 채널이 같아야 하고,
-- live_id가 NULL이면 검사하지 않는다.
ALTER TABLE platform.messages
	ADD COLUMN live_id uuid,
	ADD CONSTRAINT messages_live_channel_fk
		FOREIGN KEY (live_id, channel_id) REFERENCES platform.lives (id, channel_id);

CREATE INDEX messages_live_sent_at_idx
	ON platform.messages (live_id, sent_at DESC);

CREATE INDEX messages_live_flood_idx
	ON platform.messages (live_id, author_id, normalized_text, sent_at);
