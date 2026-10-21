package com.lifeguards.liveguard.guard.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

@Repository
public class AgentOutboxRepository {

	public record Delivery(UUID id, AgentTaskEnvelope envelope, int attemptCount, UUID leaseToken,
			boolean uncertain) {
	}

	private final JdbcClient jdbc;
	private final JsonMapper mapper;

	public AgentOutboxRepository(JdbcClient jdbc, JsonMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public UUID enqueue(AgentTaskEnvelope envelope, Instant now) {
		UUID id = UUID.randomUUID();
		String payload = mapper.writeValueAsString(envelope);
		var inserted = jdbc.sql("""
				INSERT INTO guard.agent_task_outbox
					(id, operation_id, event_id, task_id, case_id, payload, next_attempt_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
				ON CONFLICT DO NOTHING RETURNING id
				""").params(id, envelope.operationId(), envelope.eventId(), envelope.taskId(), envelope.caseId(),
						payload, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now))
				.query(UUID.class).optional();
		if (inserted.isPresent()) {
			return inserted.get();
		}
		return jdbc.sql("""
				SELECT id FROM guard.agent_task_outbox WHERE operation_id = ? AND payload = ?::jsonb
				""").params(envelope.operationId(), payload).query(UUID.class).optional()
				.orElseThrow(() -> new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
						"Agent task identifiers are already used with a different payload"));
	}

	@Transactional
	public Optional<Delivery> claim(Instant now, Instant leaseUntil) {
		return jdbc.sql("""
				WITH ready AS (
					SELECT id FROM guard.agent_task_outbox
					WHERE (status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now)
						OR (status = 'IN_FLIGHT' AND lease_until <= :now)
					ORDER BY next_attempt_at, created_at, id FOR UPDATE SKIP LOCKED LIMIT 1
				)
				UPDATE guard.agent_task_outbox o
				SET status = 'IN_FLIGHT', attempt_count = attempt_count + 1,
					delivery_uncertain = delivery_uncertain OR status = 'IN_FLIGHT',
					lease_token = :token, lease_until = :leaseUntil, updated_at = :now
				FROM ready WHERE o.id = ready.id
				RETURNING o.id, o.payload::text, o.attempt_count, o.lease_token, o.delivery_uncertain
				""").param("now", Timestamp.from(now)).param("token", UUID.randomUUID())
				.param("leaseUntil", Timestamp.from(leaseUntil))
				.query((rs, n) -> new Delivery(rs.getObject("id", UUID.class),
						mapper.readValue(rs.getString("payload"), AgentTaskEnvelope.class),
						rs.getInt("attempt_count"), rs.getObject("lease_token", UUID.class),
						rs.getBoolean("delivery_uncertain"))).optional();
	}

	@Transactional
	public boolean finish(Delivery delivery, String status, String errorCode, Instant nextAttempt, Instant now,
			boolean uncertain) {
		return jdbc.sql("""
				UPDATE guard.agent_task_outbox
				SET status = ?, last_error_code = ?, next_attempt_at = ?, updated_at = ?,
					delivery_uncertain = delivery_uncertain OR ?,
					lease_token = NULL, lease_until = NULL
				WHERE id = ? AND status = 'IN_FLIGHT' AND lease_token = ? AND lease_until > ?
				""").params(status, errorCode, Timestamp.from(nextAttempt), Timestamp.from(now),
						uncertain, delivery.id(), delivery.leaseToken(), Timestamp.from(now)).update() == 1;
	}
}
