CREATE TABLE guard.agent_task_outbox (
	id uuid PRIMARY KEY,
	operation_id text NOT NULL UNIQUE,
	event_id uuid NOT NULL UNIQUE,
	task_id uuid NOT NULL,
	case_id uuid NOT NULL REFERENCES guard.cases(id),
	payload jsonb NOT NULL,
	status text NOT NULL DEFAULT 'PENDING'
		CHECK (status IN ('PENDING', 'IN_FLIGHT', 'RETRY', 'ACCEPTED', 'FAILED', 'UNKNOWN')),
	attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
	next_attempt_at timestamptz NOT NULL,
	lease_token uuid,
	lease_until timestamptz,
	last_error_code text,
	delivery_uncertain boolean NOT NULL DEFAULT false,
	created_at timestamptz NOT NULL,
	updated_at timestamptz NOT NULL,
	CHECK ((status = 'IN_FLIGHT') = (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);

CREATE INDEX agent_task_outbox_ready_idx
	ON guard.agent_task_outbox(next_attempt_at, created_at)
	WHERE status IN ('PENDING', 'RETRY', 'IN_FLIGHT');
CREATE INDEX agent_task_outbox_task_idx ON guard.agent_task_outbox(task_id);
