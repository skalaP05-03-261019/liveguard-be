package com.lifeguards.liveguard.guard;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/**
 * Persistence boundary only: callers must validate rule/operator authority before confirming.
 * Candidate filter results and AI proposals never become confirmed findings implicitly.
 */
@Service
public class FindingService {

	public record FindingInput(UUID caseId, List<Category> categories, Verdict verdict, String policyVersion,
			String evidenceReference, int evidenceVersion, String decisionReference, String reason) {
		public FindingInput {
			DtoValidation.requireNonNull(caseId, "caseId");
			DtoValidation.requireNonNull(verdict, "verdict");
			categories = DtoValidation.immutableList(categories, "categories");
			DtoValidation.requireNotEmpty(categories, "categories");
			if (categories.stream().distinct().count() != categories.size() || evidenceVersion < 1) {
				throw new InvalidContractException("Distinct categories and a positive evidence version are required");
			}
			policyVersion = DtoValidation.requireText(policyVersion, "policyVersion");
			evidenceReference = DtoValidation.requireText(evidenceReference, "evidenceReference");
			decisionReference = DtoValidation.requireText(decisionReference, "decisionReference");
			reason = DtoValidation.requireText(reason, "reason");
		}
	}

	private final JdbcClient jdbc;
	private final Clock clock;

	public FindingService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional
	public List<UUID> propose(FindingInput input) {
		return store(input, false);
	}

	@Transactional
	public List<UUID> confirm(FindingInput input) {
		return store(input, true);
	}

	private List<UUID> store(FindingInput input, boolean confirmed) {
		DtoValidation.requireNonNull(input, "input");
		int version = jdbc.sql("SELECT evidence_version FROM guard.cases WHERE id = ? FOR UPDATE")
				.param(input.caseId()).query(Integer.class).optional()
				.orElseThrow(() -> new InvalidContractException("Case does not exist"));
		if (version != input.evidenceVersion()) {
			throw new InvalidContractException("Finding evidence version is stale");
		}
		String state = confirmed ? "CONFIRMED" : "PROPOSED";
		List<UUID> ids = input.categories().stream().map(category -> {
			UUID id = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO guard.findings(id, case_id, category, source, verdict, status,
						policy_version, evidence_reference, evidence_version, created_at, confirmed_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""")
					.params(id, input.caseId(), category.name(), confirmed ? "BACKEND" : "AI_PROPOSAL",
							input.verdict().name(), state, input.policyVersion(), input.evidenceReference(),
							input.evidenceVersion(),
							Timestamp.from(clock.instant()), confirmed ? Timestamp.from(clock.instant()) : null).update();
			event(id, state, input.decisionReference(), input.reason());
			return id;
		}).toList();
		if (confirmed) {
			// Judgment closes grouping; it does not imply a hide or a human review.
			jdbc.sql("UPDATE guard.cases SET judged_at = ?, version = version + 1 WHERE id = ?")
					.params(Timestamp.from(clock.instant()), input.caseId()).update();
		}
		return ids;
	}

	@Transactional
	public void revoke(UUID findingId, String decisionReference, String reason) {
		DtoValidation.requireNonNull(findingId, "findingId");
		decisionReference = DtoValidation.requireText(decisionReference, "decisionReference");
		reason = DtoValidation.requireText(reason, "reason");
		UUID caseId = jdbc.sql("SELECT case_id FROM guard.findings WHERE id = ?")
				.param(findingId).query(UUID.class).optional()
				.orElseThrow(() -> new InvalidContractException("Finding does not exist"));
		jdbc.sql("SELECT id FROM guard.cases WHERE id = ? FOR UPDATE")
				.param(caseId).query(UUID.class).single();
		int updated = jdbc.sql("UPDATE guard.findings SET status = 'REVOKED', revoked_at = ? "
				+ "WHERE id = ? AND source = 'BACKEND' AND status = 'CONFIRMED'")
				.params(Timestamp.from(clock.instant()),findingId).update();
		if (updated != 1) {
			throw new InvalidContractException("Only a confirmed backend finding can be revoked");
		}
		event(findingId, "REVOKED", decisionReference, reason);
		jdbc.sql("UPDATE guard.cases SET version = version + 1 WHERE id = ?").param(caseId).update();
	}

	/** Confirmed history only; kick reservations/consumption and live scoping belong to the review query. */
	@Transactional(readOnly = true)
	public List<UUID> confirmedCaseIds(UUID channelId, UUID userId, Instant since, Instant asOf) {
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(userId, "userId");
		DtoValidation.requireNonNull(since, "since");
		DtoValidation.requireNonNull(asOf, "asOf");
		if (since.isAfter(asOf)) {
			throw new InvalidContractException("History start must not be after its end");
		}
		return jdbc.sql("""
				SELECT c.id FROM guard.cases c
				WHERE c.channel_id = ? AND c.user_id = ?
					AND EXISTS (SELECT 1 FROM guard.findings f WHERE f.case_id = c.id
						AND f.source = 'BACKEND' AND f.status = 'CONFIRMED' AND f.verdict = 'VIOLATION'
						AND f.revoked_at IS NULL AND f.confirmed_at > ? AND f.confirmed_at <= ?)
				ORDER BY c.occurred_at, c.id
				""").params(channelId, userId, Timestamp.from(since), Timestamp.from(asOf))
				.query(UUID.class).list();
	}

	private void event(UUID findingId, String type, String decisionReference, String reason) {
		jdbc.sql("""
				INSERT INTO guard.finding_events(id, finding_id, event_type, decision_reference, reason, created_at)
				VALUES (?, ?, ?, ?, ?, ?)
				""").params(UUID.randomUUID(), findingId, type, decisionReference, reason,
						Timestamp.from(clock.instant())).update();
	}
}
