CREATE TABLE guard.cases (
	id uuid PRIMARY KEY,
	channel_id uuid NOT NULL REFERENCES platform.channels(id),
	user_id uuid NOT NULL REFERENCES platform.users(id),
	live_id uuid,
	policy_version text,
	handling_route text CHECK (handling_route IN
		('PASS', 'RULE_CONFIRMED', 'CONTEXT_REVIEW', 'PII_INVESTIGATION', 'FLOOD_REVIEW')),
	status text NOT NULL DEFAULT 'ANALYZING' CHECK (status IN
		('ANALYZING', 'NO_ACTION', 'HIDDEN', 'NEEDS_REVIEW', 'KICK_REVIEW_PENDING', 'CLOSED', 'FAILED')),
	snapshot_id uuid NOT NULL,
	evidence_version integer NOT NULL DEFAULT 1 CHECK (evidence_version > 0),
	version integer NOT NULL DEFAULT 1 CHECK (version > 0),
	judged_at timestamptz,
	occurred_at timestamptz NOT NULL,
	created_at timestamptz NOT NULL
);

CREATE INDEX cases_user_channel_time_idx ON guard.cases(user_id, channel_id, occurred_at DESC);

CREATE TABLE guard.candidates (
	message_id uuid PRIMARY KEY REFERENCES platform.messages(id) ON DELETE CASCADE,
	case_id uuid NOT NULL REFERENCES guard.cases(id),
	filter_result text NOT NULL CHECK (filter_result IN ('REVIEW', 'HOLD', 'FLOOD')),
	matching_tags text[] NOT NULL,
	filter_version text NOT NULL CHECK (btrim(filter_version) <> ''),
	related_message_ids uuid[] NOT NULL,
	sent_at timestamptz NOT NULL,
	snapshot_id uuid NOT NULL UNIQUE,
	evidence_version integer NOT NULL CHECK (evidence_version > 0)
);
CREATE INDEX candidates_case_time_idx ON guard.candidates(case_id, sent_at, message_id);

-- Evidence may appear in a newer case, but a message belongs to only one case.
CREATE TABLE guard.case_messages (
	case_id uuid NOT NULL REFERENCES guard.cases(id),
	message_id uuid NOT NULL REFERENCES platform.messages(id) ON DELETE CASCADE,
	owned boolean NOT NULL,
	is_target boolean NOT NULL,
	PRIMARY KEY (case_id, message_id),
	CHECK (NOT is_target OR owned)
);
CREATE UNIQUE INDEX case_messages_owner_idx ON guard.case_messages(message_id) WHERE owned;

CREATE TABLE guard.findings (
	id uuid PRIMARY KEY,
	case_id uuid NOT NULL REFERENCES guard.cases(id),
	category text NOT NULL CHECK (category IN
		('PROFANITY', 'HARASSMENT', 'FLOOD', 'PHISHING', 'PII_EXPOSURE')),
	source text NOT NULL CHECK (source IN ('AI_PROPOSAL', 'BACKEND')),
	verdict text NOT NULL CHECK (verdict IN ('NO_VIOLATION', 'VIOLATION', 'NEEDS_REVIEW')),
	status text NOT NULL CHECK (status IN ('PROPOSED', 'CONFIRMED', 'REVOKED')),
	policy_version text NOT NULL CHECK (btrim(policy_version) <> ''),
	evidence_reference text NOT NULL CHECK (btrim(evidence_reference) <> ''),
	evidence_version integer NOT NULL CHECK (evidence_version > 0),
	created_at timestamptz NOT NULL,
	CHECK ((source = 'AI_PROPOSAL' AND status = 'PROPOSED')
		OR (source = 'BACKEND' AND status IN ('CONFIRMED', 'REVOKED')))
);
CREATE UNIQUE INDEX findings_confirmed_category_idx
	ON guard.findings(case_id, category) WHERE status = 'CONFIRMED';
CREATE INDEX findings_case_idx ON guard.findings(case_id, created_at);

CREATE TABLE guard.finding_events (
	id uuid PRIMARY KEY,
	finding_id uuid NOT NULL REFERENCES guard.findings(id),
	event_type text NOT NULL CHECK (event_type IN ('PROPOSED', 'CONFIRMED', 'REVOKED')),
	decision_reference text NOT NULL CHECK (btrim(decision_reference) <> ''),
	reason text NOT NULL CHECK (btrim(reason) <> ''),
	created_at timestamptz NOT NULL
);
CREATE INDEX finding_events_finding_idx ON guard.finding_events(finding_id, created_at);

-- Existing orphan case IDs must be reconciled before applying this migration.
ALTER TABLE platform.messages ADD CONSTRAINT messages_case_fk
	FOREIGN KEY (case_id) REFERENCES guard.cases(id);
