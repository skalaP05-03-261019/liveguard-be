package com.lifeguards.liveguard.guard;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.lifeguards.liveguard.contracts.dto.response.UserHistory.RecentCase;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.enums.Visibility;

@Repository
class CaseQueryRepository {

	record CaseRow(UUID id, UUID channelId, UUID userId, UUID liveId, UUID snapshotId, int evidenceVersion,
			int version, String policyVersion, HandlingRoute handlingRoute, String status, Instant occurredAt) {
	}

	record CandidateRow(UUID messageId, FilterResult filterResult, List<String> matchingTags,
			String filterVersion, UUID snapshotId, int evidenceVersion, Instant sentAt) {
	}

	record MessageRow(UUID id, UUID authorId, String text, Instant sentAt, Visibility visibility,
			int version, boolean owned, boolean target) {
	}

	record FindingRow(UUID id, Category category, String source, Verdict verdict, String status,
			String policyVersion, String evidenceReference, int evidenceVersion, Instant createdAt) {
	}

	record AuditRow(UUID id, UUID findingId, String eventType, String decisionReference,
			String reason, Instant createdAt) {
	}

	private final JdbcClient jdbc;

	CaseQueryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<CaseRow> find(UUID caseId) {
		return jdbc.sql("SELECT * FROM guard.cases WHERE id = ?").param(caseId)
				.query((rs, n) -> new CaseRow(rs.getObject("id", UUID.class),
						rs.getObject("channel_id", UUID.class), rs.getObject("user_id", UUID.class),
						rs.getObject("live_id", UUID.class), rs.getObject("snapshot_id", UUID.class),
						rs.getInt("evidence_version"), rs.getInt("version"), rs.getString("policy_version"),
						rs.getString("handling_route") == null ? null
								: HandlingRoute.valueOf(rs.getString("handling_route")),
						rs.getString("status"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
				.optional();
	}

	boolean hasLiveScope(CaseRow current) {
		return jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform.lives WHERE id=? AND channel_id=?)")
			.params(current.liveId(),current.channelId()).query(Boolean.class).single();
	}

	List<CandidateRow> candidates(UUID caseId) {
		return jdbc.sql("SELECT * FROM guard.candidates WHERE case_id = ? ORDER BY evidence_version, message_id")
				.param(caseId)
				.query((rs, n) -> new CandidateRow(rs.getObject("message_id", UUID.class),
						FilterResult.valueOf(rs.getString("filter_result")),
						List.copyOf(Arrays.asList((String[]) rs.getArray("matching_tags").getArray())),
						rs.getString("filter_version"), rs.getObject("snapshot_id", UUID.class),
						rs.getInt("evidence_version"), rs.getObject("sent_at", OffsetDateTime.class).toInstant()))
				.list();
	}

	List<MessageRow> messages(UUID caseId) {
		return jdbc.sql("""
				SELECT m.id, m.author_id, m.text, m.sent_at, m.visibility, m.version, cm.owned, cm.is_target
				FROM guard.case_messages cm JOIN platform.messages m ON m.id = cm.message_id
				WHERE cm.case_id = ? ORDER BY m.sent_at, m.id
				""").param(caseId)
				.query((rs, n) -> new MessageRow(rs.getObject("id", UUID.class),
						rs.getObject("author_id", UUID.class), rs.getString("text"),
						rs.getObject("sent_at", OffsetDateTime.class).toInstant(),
						Visibility.valueOf(rs.getString("visibility")), rs.getInt("version"),
						rs.getBoolean("owned"), rs.getBoolean("is_target")))
				.list();
	}

	List<FindingRow> findings(UUID caseId) {
		return jdbc.sql("SELECT * FROM guard.findings WHERE case_id = ? ORDER BY created_at, id")
				.param(caseId)
				.query((rs, n) -> new FindingRow(rs.getObject("id", UUID.class),
						Category.valueOf(rs.getString("category")), rs.getString("source"),
						Verdict.valueOf(rs.getString("verdict")), rs.getString("status"),
						rs.getString("policy_version"), rs.getString("evidence_reference"),
						rs.getInt("evidence_version"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
				.list();
	}

	List<AuditRow> audit(UUID caseId) {
		return jdbc.sql("""
				SELECT e.* FROM guard.finding_events e JOIN guard.findings f ON f.id = e.finding_id
				WHERE f.case_id = ? ORDER BY e.created_at, e.id
				""").param(caseId)
				.query((rs, n) -> new AuditRow(rs.getObject("id", UUID.class),
						rs.getObject("finding_id", UUID.class), rs.getString("event_type"),
						rs.getString("decision_reference"), rs.getString("reason"),
						rs.getObject("created_at", OffsetDateTime.class).toInstant()))
				.list();
	}

	List<RecentCase> timeoutHistory(CaseRow current, Instant since, Instant asOf) {
		return jdbc.sql("""
				SELECT c.id, f.category, max(f.confirmed_at) AS judged_at
				FROM guard.cases c JOIN guard.findings f ON f.case_id = c.id
				WHERE c.live_id = ? AND c.user_id = ? AND c.id <> ?
					AND f.confirmed_at > ? AND f.confirmed_at <= ?
					AND f.source = 'BACKEND' AND f.status = 'CONFIRMED' AND f.verdict = 'VIOLATION'
					AND f.revoked_at IS NULL
				GROUP BY c.id, f.category ORDER BY judged_at, c.id, f.category
				""").params(current.liveId(), current.userId(), current.id(),
						Timestamp.from(since), Timestamp.from(asOf))
				.query((rs, n) -> new RecentCase(rs.getObject("id", UUID.class),
						Category.valueOf(rs.getString("category")),
						rs.getObject("judged_at", OffsetDateTime.class).toInstant())).list();
	}

	List<UUID> kickBasis(UUID channelId, UUID userId, UUID liveId, Instant since, Instant asOf) {
		return jdbc.sql("""
				SELECT c.id FROM guard.cases c
				WHERE c.channel_id = ? AND c.user_id = ? AND c.live_id = ?
					AND EXISTS (SELECT 1 FROM platform.lives l WHERE l.id=c.live_id AND l.ended_at IS NULL)
					AND EXISTS (SELECT 1 FROM guard.findings f WHERE f.case_id = c.id
						AND f.source = 'BACKEND' AND f.status = 'CONFIRMED' AND f.verdict = 'VIOLATION'
						AND f.revoked_at IS NULL AND f.confirmed_at > ? AND f.confirmed_at <= ?)
					AND NOT EXISTS (SELECT 1 FROM guard.live_reservations r WHERE r.case_id=c.id
						AND r.state IN ('RESERVED','CONSUMED'))
				ORDER BY (SELECT min(f.confirmed_at) FROM guard.findings f WHERE f.case_id=c.id
					AND f.source='BACKEND' AND f.status='CONFIRMED' AND f.verdict='VIOLATION'
					AND f.revoked_at IS NULL AND f.confirmed_at > ? AND f.confirmed_at <= ?), c.id
				""").params(channelId, userId, liveId, Timestamp.from(since), Timestamp.from(asOf),
						Timestamp.from(since), Timestamp.from(asOf)).query(UUID.class).list();
	}
}
