CREATE TABLE platform.users (
	id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	username      text NOT NULL UNIQUE,
	password_hash text NOT NULL,
	nickname      text NOT NULL,
	role          text NOT NULL CHECK (role IN ('VIEWER', 'MODERATOR', 'PLATFORM_ADMIN'))
);

CREATE TABLE platform.channels (
	id              uuid  PRIMARY KEY DEFAULT gen_random_uuid(),
	name            text  NOT NULL,
	official_hosts  jsonb NOT NULL DEFAULT '[]'::jsonb,
	official_events jsonb NOT NULL DEFAULT '[]'::jsonb
);

CREATE TABLE platform.channel_members (
	channel_id  uuid NOT NULL REFERENCES platform.channels (id),
	user_id     uuid NOT NULL REFERENCES platform.users (id),
	member_role text NOT NULL CHECK (member_role IN ('VIEWER', 'STREAMER', 'OFFICIAL_STAFF', 'MODERATOR')),
	PRIMARY KEY (channel_id, user_id)
);

-- case_id는 guard.cases(id)를 가리키지만 guard 스키마는 B2의 V2xx 마이그레이션이 만든다.
-- FK는 B2가 해당 마이그레이션에서 추가한다.
CREATE TABLE platform.messages (
	id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
	channel_id        uuid        NOT NULL REFERENCES platform.channels (id),
	author_id         uuid        NOT NULL REFERENCES platform.users (id),
	client_message_id uuid        NOT NULL,
	text              text        NOT NULL CHECK (char_length(text) BETWEEN 1 AND 500),
	normalized_text   text        NOT NULL,
	filter_result     text        NOT NULL CHECK (filter_result IN ('PASS', 'REVIEW', 'HOLD', 'FLOOD')),
	visibility        text        NOT NULL CHECK (visibility IN ('PUBLIC', 'HELD', 'HIDDEN')),
	case_id           uuid,
	version           integer     NOT NULL DEFAULT 1,
	sent_at           timestamptz NOT NULL,
	UNIQUE (channel_id, author_id, client_message_id)
);

CREATE INDEX messages_channel_sent_at_idx
	ON platform.messages (channel_id, sent_at DESC);

CREATE INDEX messages_flood_idx
	ON platform.messages (channel_id, author_id, normalized_text, sent_at);
