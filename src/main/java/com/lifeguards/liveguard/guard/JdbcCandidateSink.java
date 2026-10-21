package com.lifeguards.liveguard.guard;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.platform.port.CandidateRequest;
import com.lifeguards.liveguard.platform.port.CandidateSink;

@Service
public class JdbcCandidateSink implements CandidateSink {

	private final JdbcClient jdbc;
	private final Clock clock;

	public JdbcCandidateSink(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional
	public UUID open(CandidateRequest request) {
		DtoValidation.requireNonNull(request, "request");
		if (request.filterResult() == FilterResult.PASS) {
			throw new InvalidContractException("PASS messages cannot open a case");
		}
		if (request.filterResult() != FilterResult.FLOOD && !request.relatedMessageIds().isEmpty()) {
			throw new InvalidContractException("Only FLOOD candidates may contain related messages");
		}
		if (request.relatedMessageIds().contains(request.messageId())
				|| request.relatedMessageIds().stream().distinct().count() != request.relatedMessageIds().size()) {
			throw new InvalidContractException("Related messages must be distinct and exclude the current message");
		}
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
				.param(request.channelId() + ":" + request.userId())
				.query((rs, row) -> true).single();
		var existing = jdbc.sql("SELECT case_id FROM guard.candidates WHERE message_id = ?")
				.param(request.messageId()).query(UUID.class).optional();
		if (existing.isPresent()) {
			UUID caseId = existing.get();
			boolean sameScope = jdbc.sql("SELECT EXISTS (SELECT 1 FROM guard.cases "
					+ "WHERE id = ? AND channel_id = ? AND user_id = ?)")
					.params(caseId, request.channelId(), request.userId()).query(Boolean.class).single();
			if (!sameScope) {
				throw new InvalidContractException("Candidate message belongs to another scope");
			}
			return caseId;
		}

		UUID caseId = reusableCase(request);
		UUID snapshotId = UUID.randomUUID();
		int evidenceVersion;
		if (caseId == null) {
			caseId = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO guard.cases
						(id, channel_id, user_id, snapshot_id, occurred_at, created_at)
					VALUES (?, ?, ?, ?, ?, ?)
					""")
					.params(caseId, request.channelId(), request.userId(), snapshotId,
							Timestamp.from(request.sentAt()), Timestamp.from(clock.instant())).update();
			evidenceVersion = 1;
		} else {
			evidenceVersion = jdbc.sql("""
					UPDATE guard.cases SET evidence_version = evidence_version + 1,
						version = version + 1, snapshot_id = ? WHERE id = ?
					RETURNING evidence_version
					""").params(snapshotId, caseId).query(Integer.class).single();
		}
		jdbc.sql("""
				INSERT INTO guard.candidates
					(message_id, case_id, filter_result, matching_tags, filter_version,
						related_message_ids, sent_at, snapshot_id, evidence_version)
				VALUES (?, ?, ?, string_to_array(?, ','), ?, string_to_array(?, ',')::uuid[], ?, ?, ?)
				""")
				.params(request.messageId(), caseId, request.filterResult().name(),
						String.join(",", request.matchingTags()), request.filterVersion(),
						String.join(",", request.relatedMessageIds().stream().map(UUID::toString).toList()),
						Timestamp.from(request.sentAt()), snapshotId, evidenceVersion).update();
		for (UUID messageId : request.relatedMessageIds()) {
			link(caseId, messageId, false);
		}
		link(caseId, request.messageId(), true);
		return caseId;
	}

	private UUID reusableCase(CandidateRequest request) {
		if (request.filterResult() != FilterResult.FLOOD || request.relatedMessageIds().isEmpty()) {
			return null;
		}
		List<UUID> related = request.relatedMessageIds();
		for (int i = related.size() - 1; i >= 0; i--) {
			var found = jdbc.sql("""
					SELECT c.id FROM guard.case_messages m JOIN guard.cases c ON c.id = m.case_id
					WHERE m.message_id = ? AND m.owned AND c.channel_id = ? AND c.user_id = ?
						AND c.status = 'ANALYZING' AND c.judged_at IS NULL
					FOR UPDATE OF c
					""").params(related.get(i), request.channelId(), request.userId())
					.query(UUID.class).optional();
			if (found.isPresent()) {
				return found.get();
			}
		}
		return null;
	}

	private void link(UUID caseId, UUID messageId, boolean target) {
		boolean ownedElsewhere = jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM guard.case_messages
					WHERE message_id = ? AND owned AND case_id <> ?)
				""").params(messageId, caseId).query(Boolean.class).single();
		if (target && ownedElsewhere) {
			throw new InvalidContractException("Target message already belongs to another case");
		}
		jdbc.sql("""
				INSERT INTO guard.case_messages(case_id, message_id, owned, is_target)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (case_id, message_id) DO UPDATE
					SET is_target = guard.case_messages.is_target OR EXCLUDED.is_target
				""").params(caseId, messageId, !ownedElsewhere, target).update();
	}
}
