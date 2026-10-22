package com.lifeguards.liveguard.guard.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
	private final java.time.Clock clock;

	public AgentOutboxRepository(JdbcClient jdbc, JsonMapper mapper, java.time.Clock clock) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.clock=clock;
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
		return claim(null, now, leaseUntil);
	}

	@Transactional
	public Optional<Delivery> claim(UUID id, Instant now, Instant leaseUntil) {
        if(id!=null) {
            // A Rabbit hint addresses one row: wait for the publisher's short update instead of
            // acknowledging a skipped row. Calculate the lease after that wait, not before it.
            var duration=java.time.Duration.between(now,leaseUntil);
            if(jdbc.sql("SELECT id FROM guard.agent_task_outbox WHERE id=? FOR UPDATE")
                    .param(id).query(UUID.class).optional().isEmpty())return Optional.empty();
            now=clock.instant();leaseUntil=now.plus(duration);
        }
		return jdbc.sql("""
				WITH ready AS (
					SELECT id FROM guard.agent_task_outbox
					WHERE (CAST(:id AS uuid) IS NULL OR id = :id)
						AND ((status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now)
						OR (status = 'IN_FLIGHT' AND lease_until <= :now))
					ORDER BY next_attempt_at, created_at, id FOR UPDATE %s LIMIT 1
				)
				UPDATE guard.agent_task_outbox o
				SET status = 'IN_FLIGHT', attempt_count = attempt_count + 1,
					delivery_uncertain = delivery_uncertain OR status = 'IN_FLIGHT',
					publish_token = NULL, publish_lease_until = NULL,
					lease_token = :token, lease_until = :leaseUntil, updated_at = :now
				FROM ready WHERE o.id = ready.id
				RETURNING o.id, o.payload::text, o.attempt_count, o.lease_token, o.delivery_uncertain
				""".formatted(id == null ? "SKIP LOCKED" : "")).param("id", id).param("now", Timestamp.from(now)).param("token", UUID.randomUUID())
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
					notification_after = ?,
					delivery_uncertain = delivery_uncertain OR ?,
					lease_token = NULL, lease_until = NULL
				WHERE id = ? AND status = 'IN_FLIGHT' AND lease_token = ? AND lease_until > ?
				""").params(status, errorCode, Timestamp.from(nextAttempt), Timestamp.from(now),
						Timestamp.from(nextAttempt), uncertain, delivery.id(), delivery.leaseToken(), Timestamp.from(now)).update() == 1;
	}

	/** 전송 문제로 멈춘 것으로 보는 오류 코드. Agent가 다시 뜨면 보낼 수 있다. 4xx·충돌·대체·방송 종료는 아니다. */
	static final List<String> TRANSIENT_CODES = List.of("AGENT_UNREACHABLE", "AGENT_TRANSPORT_ERROR",
			"AGENT_RESPONSE_TIMEOUT", "DELIVERY_BUDGET_EXHAUSTED", "AGENT_HTTP_408", "AGENT_HTTP_429",
			"AGENT_HTTP_502", "AGENT_HTTP_503", "AGENT_HTTP_504");

	/**
	 * 전송 문제로 재시도 예산을 다 쓰고 멈춘 작업(UNKNOWN·FAILED) 중 아직 그 사건의 현재 작업인 것을 다시 보낼 수 있게
	 * 한다. staleBefore 이전에 마지막으로 바뀐 행만 고르므로 Agent가 계속 내려가 있어도 그 간격으로만 다시 시도한다.
	 * 전달이 불확실했던 행은 표시를 유지해, 다음 전달이 먼저 상태를 조회하고 없을 때만 보낸다(중복 실행 없음).
	 */
	@Transactional
	public int revive(Instant now, Instant staleBefore) {
		return jdbc.sql("""
				UPDATE guard.agent_task_outbox o
				SET status = 'RETRY', attempt_count = 0, next_attempt_at = :now, notification_after = :now,
					updated_at = :now
				WHERE o.status IN ('UNKNOWN', 'FAILED') AND o.last_error_code IN (:codes)
					AND o.updated_at <= :staleBefore
					AND EXISTS (SELECT 1 FROM guard.live_case_state s
						WHERE s.case_id = o.case_id AND s.task_id = o.task_id)
				""").param("now", Timestamp.from(now)).param("codes", TRANSIENT_CODES)
				.param("staleBefore", Timestamp.from(staleBefore)).update();
	}

	public record Publication(UUID id, UUID token) {
	}

	@Transactional
	public Optional<Publication> claimPublication(Instant now, Instant leaseUntil) {
		return jdbc.sql("""
				WITH ready AS (
					SELECT id FROM guard.agent_task_outbox
					WHERE ((status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now)
						OR (status = 'IN_FLIGHT' AND lease_until <= :now))
						AND notification_after <= :now
						AND (publish_lease_until IS NULL OR publish_lease_until <= :now)
					ORDER BY notification_after, next_attempt_at, id
					FOR UPDATE SKIP LOCKED LIMIT 1
				)
				UPDATE guard.agent_task_outbox o
				SET publish_token = :token, publish_lease_until = :until
				FROM ready WHERE o.id = ready.id
				RETURNING o.id, o.publish_token
				""").param("now", Timestamp.from(now)).param("until", Timestamp.from(leaseUntil))
				.param("token", UUID.randomUUID())
				.query((rs, n) -> new Publication(rs.getObject("id", UUID.class),
						rs.getObject("publish_token", UUID.class))).optional();
	}

	@Transactional
	public boolean finishPublication(Publication publication, Instant nextNotification, Instant now) {
		return jdbc.sql("""
				UPDATE guard.agent_task_outbox
				SET notification_after = ?, publish_token = NULL, publish_lease_until = NULL
				WHERE id = ? AND publish_token = ? AND publish_lease_until > ?
				""").params(Timestamp.from(nextNotification), publication.id(), publication.token(),
						Timestamp.from(now)).update() == 1;
	}
}
