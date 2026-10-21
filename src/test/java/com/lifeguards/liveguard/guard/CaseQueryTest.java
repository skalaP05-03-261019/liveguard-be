package com.lifeguards.liveguard.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.guard.FindingService.FindingInput;
import com.lifeguards.liveguard.platform.port.CandidateRequest;
import com.lifeguards.liveguard.platform.port.CandidateSink;

@SpringBootTest(properties = "liveguard.internal.service-token=query-test-token")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CaseQueryTest.FixedClock.class})
@Transactional
class CaseQueryTest {

	private static final Instant NOW = Instant.parse("2026-10-21T02:00:00Z");
	private static final UUID CHANNEL = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID OTHER_CHANNEL = UUID.fromString("20000000-0000-4000-8000-000000000002");
	private static final UUID USER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID OTHER_USER = UUID.fromString("10000000-0000-4000-8000-000000000002");
	private static final UUID LIVE = UUID.randomUUID();

	@TestConfiguration
	static class FixedClock {
		@Bean
		@Primary
		Clock queryClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private CaseQueryService queries;
	@Autowired
	private FindingService findings;
	@Autowired
	private CandidateSink sink;
	@Autowired
	private MockMvc mockMvc;

	private UUID current;

	@BeforeEach
	void setUp() {
		current = create(CHANNEL, USER, LIVE, NOW);
	}

	@Test
	void readsPersistedEvidenceTargetsFindingsAndAudit() {
		confirm(current, Category.PROFANITY, Category.HARASSMENT);
		var details = queries.details(current);
		assertEquals(current, details.metadata().id());
		assertEquals("norm-test+dict-test", details.candidates().getFirst().filterVersion());
		assertEquals(1, details.messages().size());
		assertTrue(details.messages().getFirst().owned());
		assertTrue(details.messages().getFirst().target());
		assertEquals(2, details.findings().size());
		assertEquals(2, details.audit().size());
		assertTrue(details.findings().stream().allMatch(row -> row.evidenceReference().equals("evidence-reference")));
	}

	@Test
	void caseResponseMasksPiiAndDerivesBothTargetProjectionsFromCanonicalTargets() throws Exception {
		var details = queries.details(current);
		UUID target = details.messages().getFirst().id();
		jdbc.update("UPDATE platform.messages SET visibility = 'HIDDEN', version = 3 WHERE id = ?", target);
		mockMvc.perform(get("/internal/cases/{id}", current)
						.header("X-Service-Token", "query-test-token").header("X-Trace-Id", current))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.traceId").value(current.toString()))
				.andExpect(jsonPath("$.data.liveId").value(LIVE.toString()))
				.andExpect(jsonPath("$.data.snapshotId").value(details.metadata().snapshotId().toString()))
				.andExpect(jsonPath("$.data.evidenceVersion").value(1))
				.andExpect(jsonPath("$.data.messages[0].authorAlias").value("U1"))
				.andExpect(jsonPath("$.data.targets[0].messageId").value(target.toString()))
				.andExpect(jsonPath("$.data.targets[0].version").value(3))
				.andExpect(jsonPath("$.data.targets[0].visibility").value("HIDDEN"))
				.andExpect(jsonPath("$.data.targetMessageIds[0]").value(target.toString()))
				.andExpect(jsonPath("$.data.targetVersions['" + target + "']").value(3));
		String masked = queries.moderationCase(current).messages().getFirst().text();
		assertFalse(masked.contains("010-1234-5678"));
		assertFalse(masked.contains("person@example.com"));
	}

	@Test
	void missingCaseAndMissingMetadataHaveDifferentExplicitErrors() throws Exception {
		mockMvc.perform(get("/internal/cases/{id}", UUID.randomUUID())
						.header("X-Service-Token", "query-test-token"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
		for (String column : List.of("live_id", "policy_version", "handling_route")) {
			UUID incomplete = create(CHANNEL, USER, LIVE, NOW);
			jdbc.update("UPDATE guard.cases SET " + column + " = NULL WHERE id = ?", incomplete);
			mockMvc.perform(get("/internal/cases/{id}", incomplete)
							.header("X-Service-Token", "query-test-token"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.error.code").value("CASE_NOT_READY"))
					.andExpect(jsonPath("$.error.retryable").value(false))
					.andExpect(jsonPath("$.data").doesNotExist());
		}
	}

	@Test
	void missingCurrentEvidenceOrTargetsCannotProduceASuccessSnapshot() {
		jdbc.update("UPDATE guard.cases SET snapshot_id = ? WHERE id = ?", UUID.randomUUID(), current);
		assertEquals(ErrorCode.CASE_NOT_READY,
				assertThrows(ApiException.class, () -> queries.moderationCase(current)).code());
		UUID noTargets = create(CHANNEL, USER, LIVE, NOW);
		jdbc.update("UPDATE guard.case_messages SET is_target = false WHERE case_id = ?", noTargets);
		assertEquals(ErrorCode.CASE_NOT_READY,
				assertThrows(ApiException.class, () -> queries.moderationCase(noTargets)).code());
	}

	@Test
	void historyExcludesCurrentAndSeparatesDistinctCaseCountFromCategoryCounts() {
		confirm(current, Category.PROFANITY);
		UUID past = create(CHANNEL, USER, LIVE, NOW.minusSeconds(60));
		confirm(past, Category.PROFANITY, Category.HARASSMENT);
		var history = queries.timeoutHistory(current, NOW.minusSeconds(3600), NOW);
		assertEquals(1, history.violationCount());
		assertEquals(2, history.recentCases().size());
		assertEquals(1L, history.categoryCounts().get(Category.PROFANITY));
		assertEquals(1L, history.categoryCounts().get(Category.HARASSMENT));
		assertEquals(0L, history.categoryCounts().get(Category.PII_EXPOSURE));
	}

	@Test
	void allFiveCategoriesCountOnceForKickAndOnceInEachHistoryCategory() {
		UUID past = create(CHANNEL, USER, LIVE, NOW.minusSeconds(10));
		confirm(past, Category.values());
		var history = queries.timeoutHistory(current, NOW.minusSeconds(60), NOW);
		assertEquals(1, history.violationCount());
		assertEquals(5, history.recentCases().size());
		assertTrue(history.categoryCounts().values().stream().allMatch(count -> count == 1));
		assertEquals(List.of(past), queries.kickBasis(CHANNEL, USER, LIVE, NOW).confirmedCaseIds());
	}

	@Test
	void reconfirmedCategoryDoesNotDuplicateTheCaseOrCategoryRow() {
		UUID past = create(CHANNEL, USER, LIVE, NOW.minusSeconds(10));
		UUID original = confirm(past, Category.PROFANITY).getFirst();
		findings.revoke(original, "revoke-first", "Initial finding canceled");
		confirm(past, Category.PROFANITY);
		var history = queries.timeoutHistory(current, NOW.minusSeconds(60), NOW);
		assertEquals(1, history.violationCount());
		assertEquals(1, history.recentCases().size());
		assertEquals(1L, history.categoryCounts().get(Category.PROFANITY));
		assertEquals(List.of(past), queries.kickBasis(CHANNEL, USER, LIVE, NOW).confirmedCaseIds());
	}

	@Test
	void proposalsNormalReviewAndRevokedFindingsDoNotIncreaseHistory() {
		for (Verdict verdict : Verdict.values()) {
			UUID proposal = create(CHANNEL, USER, LIVE, NOW.minusSeconds(10));
			findings.propose(input(proposal, verdict, Category.FLOOD));
		}
		for (Verdict verdict : List.of(Verdict.NO_VIOLATION, Verdict.NEEDS_REVIEW)) {
			UUID normal = create(CHANNEL, USER, LIVE, NOW.minusSeconds(10));
			findings.confirm(input(normal, verdict, Category.FLOOD));
		}
		UUID revoked = create(CHANNEL, USER, LIVE, NOW.minusSeconds(10));
		findings.revoke(confirm(revoked, Category.FLOOD).getFirst(), "revoke", "Incorrect finding");
		assertTrue(queries.timeoutHistory(current, NOW.minusSeconds(3600), NOW).recentCases().isEmpty());
		assertTrue(queries.kickBasis(CHANNEL, USER, LIVE, NOW).confirmedCaseIds().isEmpty());
		assertEquals(2, queries.details(revoked).audit().size());
	}

	@Test
	void oneRevokedCategoryDoesNotEraseOtherConfirmedCategories() {
		UUID past = create(CHANNEL, USER, LIVE, NOW.minusSeconds(10));
		List<UUID> ids = confirm(past, Category.PROFANITY, Category.HARASSMENT);
		findings.revoke(ids.getFirst(), "revoke-profanity", "Incorrect profanity finding");
		var history = queries.timeoutHistory(current, NOW.minusSeconds(3600), NOW);
		assertEquals(1, history.violationCount());
		assertEquals(List.of(Category.HARASSMENT),
				history.recentCases().stream().map(row -> row.category()).toList());
		assertEquals(List.of(past), queries.kickBasis(CHANNEL, USER, LIVE, NOW).confirmedCaseIds());
	}

	@Test
	void historyAndKickQueriesRespectTheirDifferentScopes() {
		UUID sameLive = create(CHANNEL, USER, LIVE, NOW.minusSeconds(1));
		UUID nextLive = create(CHANNEL, USER, UUID.randomUUID(), NOW.minusSeconds(1));
		UUID unknownLive = create(CHANNEL, USER, null, NOW.minusSeconds(1));
		UUID otherChannel = create(OTHER_CHANNEL, USER, LIVE, NOW.minusSeconds(1));
		UUID otherUser = create(CHANNEL, OTHER_USER, LIVE, NOW.minusSeconds(1));
		for (UUID id : List.of(sameLive, nextLive, unknownLive, otherChannel, otherUser, current)) {
			confirm(id, Category.PII_EXPOSURE, Category.HARASSMENT);
		}
		var history = queries.timeoutHistory(current, NOW.minusSeconds(60), NOW);
		assertEquals(3, history.violationCount());
		assertEquals(6, history.recentCases().size());
		var basis = queries.kickBasis(CHANNEL, USER, LIVE, NOW);
		assertEquals(2, basis.confirmedCaseIds().size());
		assertTrue(basis.confirmedCaseIds().containsAll(List.of(current, sameLive)));
	}

	@Test
	void inclusiveTimeBoundsExcludeOlderFutureAndNotYetConfirmedCases() {
		Instant lower = NOW.minus(24, ChronoUnit.HOURS);
		UUID atLower = create(CHANNEL, USER, LIVE, lower);
		UUID beforeLower = create(CHANNEL, USER, LIVE, lower.minusNanos(1000));
		UUID future = create(CHANNEL, USER, LIVE, NOW.plusNanos(1000));
		UUID futureConfirmation = create(CHANNEL, USER, LIVE, NOW.minusSeconds(1));
		for (UUID id : List.of(atLower, beforeLower, future, futureConfirmation, current)) {
			confirm(id, Category.FLOOD);
		}
		jdbc.update("UPDATE guard.findings SET created_at = ? WHERE case_id = ?",
				Timestamp.from(NOW.plusNanos(1000)), futureConfirmation);
		assertEquals(List.of(atLower, current), queries.kickBasis(CHANNEL, USER, LIVE, NOW).confirmedCaseIds());
		assertEquals(List.of(atLower),
				queries.timeoutHistory(current, lower, NOW).recentCases().stream().map(row -> row.caseId()).toList());
	}

	@Test
	void explicitDaysAndLegacyAliasReturnRealHistoryButNoDefaultIsInvented() throws Exception {
		UUID past = create(CHANNEL, USER, LIVE, NOW.minusSeconds(60));
		confirm(past, Category.FLOOD);
		for (String suffix : List.of("user-history", "history")) {
			mockMvc.perform(get("/internal/cases/{id}/" + suffix, current).param("days", "31")
							.header("X-Service-Token", "query-test-token"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.windowHours").value(744))
					.andExpect(jsonPath("$.data.violationCount").value(1))
					.andExpect(jsonPath("$.data.recentCases[0].caseId").value(past.toString()));
		}
		mockMvc.perform(get("/internal/cases/{id}/user-history", current)
						.header("X-Service-Token", "query-test-token"))
				.andExpect(status().isNotImplemented())
				.andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"));
		for (String days : List.of("0", "-1", "2147483647", "not-a-number")) {
			mockMvc.perform(get("/internal/cases/{id}/user-history", current).param("days", days)
							.header("X-Service-Token", "query-test-token"))
					.andExpect(status().isBadRequest());
		}
	}

	@Test
	void incompleteCaseCanStillHaveExplicitHistoryWithoutPretendingItHasALive() {
		jdbc.update("UPDATE guard.cases SET live_id = NULL, handling_route = NULL, policy_version = NULL WHERE id = ?",
				current);
		assertEquals(0, queries.userHistory(current, 1).violationCount());
		assertThrows(InvalidContractException.class, () -> queries.kickBasis(CHANNEL, USER, null, NOW));
	}

	@Test
	void invalidRangesAreRejectedAndKickEndpointRemainsNonExecutable() throws Exception {
		assertThrows(InvalidContractException.class, () -> queries.timeoutHistory(current, NOW, NOW));
		assertThrows(InvalidContractException.class, () -> queries.timeoutHistory(current, NOW, NOW.minusSeconds(1)));
		assertThrows(InvalidContractException.class, () -> queries.timeoutHistory(current, null, NOW));
		mockMvc.perform(get("/internal/cases/{id}/kick-eligibility", current)
						.header("X-Service-Token", "query-test-token"))
				.andExpect(status().isNotImplemented())
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	private UUID create(UUID channel, UUID user, UUID live, Instant at) {
		UUID message = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO platform.messages(id, channel_id, author_id, client_message_id,
					text, normalized_text, filter_result, visibility, sent_at)
				VALUES (?, ?, ?, ?, '010-1234-5678 person@example.com', 'test', 'REVIEW', 'PUBLIC', ?)
				""", message, channel, user, UUID.randomUUID(), Timestamp.from(at));
		UUID caseId = sink.open(new CandidateRequest(channel, user, message, FilterResult.REVIEW,
				List.of("ATTACK_TERM"), "norm-test+dict-test", at, List.of()));
		jdbc.update("""
				UPDATE guard.cases SET live_id = ?, policy_version = 'test-policy',
					handling_route = 'CONTEXT_REVIEW' WHERE id = ?
				""", live, caseId);
		jdbc.update("UPDATE platform.messages SET case_id = ? WHERE id = ?", caseId, message);
		return caseId;
	}

	private List<UUID> confirm(UUID caseId, Category... categories) {
		return findings.confirm(input(caseId, Verdict.VIOLATION, categories));
	}

	private FindingInput input(UUID caseId, Verdict verdict, Category... categories) {
		return new FindingInput(caseId, List.of(categories), verdict, "test-policy", "evidence-reference",
				1, "decision-reference", "Test judgment");
	}
}
