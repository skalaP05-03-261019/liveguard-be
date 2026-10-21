ALTER TABLE guard.agent_task_outbox
	ADD COLUMN notification_after timestamptz NOT NULL DEFAULT '-infinity',
	ADD COLUMN publish_token uuid,
	ADD COLUMN publish_lease_until timestamptz,
	ADD CONSTRAINT agent_outbox_publish_lease CHECK
		((publish_token IS NULL) = (publish_lease_until IS NULL));

CREATE INDEX agent_outbox_notification_idx
	ON guard.agent_task_outbox(notification_after, next_attempt_at)
	WHERE status IN ('PENDING', 'RETRY', 'IN_FLIGHT');
