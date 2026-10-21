package com.lifeguards.liveguard.guard;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static com.lifeguards.liveguard.support.LoginSupport.withXsrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.guard.FindingService.FindingInput;
import com.lifeguards.liveguard.platform.port.CandidateRequest;
import com.lifeguards.liveguard.platform.port.CandidateSink;
import com.lifeguards.liveguard.support.CaseFixtures;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CasePersistenceTest {

	private static final UUID CHANNEL = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID USER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private CandidateSink sink;
	@Autowired
	private FindingService findings;
	@Autowired
	private TransactionTemplate transactions;
	@Autowired
	private MockMvc mockMvc;

	@AfterEach
	void cleanUp() {
		CaseFixtures.deleteCases(jdbc);
		jdbc.update("DELETE FROM platform.messages");
	}

	@Test
	void actualMessageSendingUsesTheDurableSinkAndReplayDoesNotCreateAnotherCase() throws Exception {
		assertInstanceOf(JdbcCandidateSink.class, sink);
		var viewer = login(mockMvc, "viewer");
		UUID clientId = UUID.randomUUID();
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(withXsrf(mockMvc, post("/api/channels/" + CHANNEL + "/messages"))
					.session(viewer).contentType(MediaType.APPLICATION_JSON)
					.content("{\"clientMessageId\":\"" + clientId + "\",\"text\":\"무대 미쳤다ㅋㅋ\"}"))
					.andExpect(status().isCreated());
		}
		assertEquals(1, count("guard.cases"));
		assertEquals(1, count("guard.candidates"));
		assertEquals(1, count("platform.messages"));
		assertEquals(1, jdbc.queryForObject("""
				SELECT count(*) FROM platform.messages m JOIN guard.cases c ON c.id = m.case_id
				""", Integer.class));
		assertNull(jdbc.queryForObject("SELECT live_id FROM guard.cases", UUID.class));
		assertNull(jdbc.queryForObject("SELECT policy_version FROM guard.cases", String.class));
		assertEquals(0, count("guard.findings"));
	}

	@Test
	void floodThreeFourFiveReuseOneCaseAndSeparateEvidenceFromTargets() {
		UUID first = message();
		UUID second = message();
		UUID third = message();
		CandidateRequest initial = flood(third, List.of(first, second));
		UUID caseId = sink.open(initial);
		UUID snapshot = jdbc.queryForObject("SELECT snapshot_id FROM guard.cases", UUID.class);
		UUID fourth = message();
		assertEquals(caseId, sink.open(flood(fourth, List.of(first, second, third))));
		UUID fifth = message();
		assertEquals(caseId, sink.open(flood(fifth, List.of(first, second, third, fourth))));
		assertEquals(1, count("guard.cases"));
		assertEquals(5, count("guard.case_messages"));
		assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM guard.case_messages WHERE is_target", Integer.class));
		assertEquals(3, jdbc.queryForObject("SELECT evidence_version FROM guard.cases", Integer.class));
		assertNotEquals(snapshot, jdbc.queryForObject("SELECT snapshot_id FROM guard.cases", UUID.class));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM guard.candidates WHERE snapshot_id = ?",
				Integer.class, snapshot));
		assertEquals(caseId, sink.open(initial));
		assertEquals(3, jdbc.queryForObject("SELECT evidence_version FROM guard.cases", Integer.class));
	}

	@Test
	void actualFloodSendingLinksAllMessagesWithoutHidingTheFirstTwo() throws Exception {
		var viewer = login(mockMvc, "viewer");
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(withXsrf(mockMvc, post("/api/channels/" + CHANNEL + "/messages"))
					.session(viewer).contentType(MediaType.APPLICATION_JSON)
					.content("{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"text\":\"flood test\"}"))
					.andExpect(status().isCreated());
		}
		assertEquals(1, count("guard.cases"));
		assertEquals(1, jdbc.queryForObject("SELECT count(DISTINCT case_id) FROM platform.messages", Integer.class));
		assertEquals(5, count("guard.case_messages"));
		assertEquals(List.of("PUBLIC", "PUBLIC", "HELD", "HELD", "HELD"),
				jdbc.queryForList("SELECT visibility FROM platform.messages ORDER BY sent_at, id", String.class));
		assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM guard.case_messages WHERE is_target", Integer.class));
	}

	@Test
	void latestRelatedAnalyzingCaseWinsEvenWhenSeveralCasesAreReferenced() {
		UUID first = message();
		UUID oldCase = sink.open(flood(first, List.of()));
		UUID next = message();
		UUID recentCase = sink.open(flood(next, List.of()));
		assertNotEquals(oldCase, recentCase);
		assertEquals(recentCase, sink.open(flood(message(), List.of(first, next))));
	}

	@Test
	void judgedFloodCaseIsNotReusedAndEarlierOwnershipIsPreserved() {
		UUID first = message();
		UUID oldCase = sink.open(flood(first, List.of()));
		findings.confirm(input(oldCase, List.of(Category.FLOOD), 1));
		UUID next = message();
		UUID newCase = sink.open(flood(next, List.of(first)));
		assertNotEquals(oldCase, newCase);
		assertEquals(oldCase, jdbc.queryForObject(
				"SELECT case_id FROM guard.case_messages WHERE message_id = ? AND owned", UUID.class, first));
		assertEquals(false, jdbc.queryForObject(
				"SELECT owned FROM guard.case_messages WHERE case_id = ? AND message_id = ?",
				Boolean.class, newCase, first));
		assertEquals(newCase, sink.open(flood(message(), List.of(first, next))));
	}

	@Test
	void candidateFailureRollsBackBothTheInsertedMessageAndCase() {
		assertThrows(DataIntegrityViolationException.class, () -> transactions.executeWithoutResult(status -> {
			UUID messageId = message();
			sink.open(flood(messageId, List.of(UUID.randomUUID())));
		}));
		assertEquals(0, count("platform.messages"));
		assertEquals(0, count("guard.cases"));
		assertEquals(0, count("guard.candidates"));
	}

	@Test
	void foreignKeyRejectsAnUnknownCase() {
		UUID id = message();
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"UPDATE platform.messages SET case_id = ? WHERE id = ?", UUID.randomUUID(), id));
	}

	@Test
	void proposalDoesNotCountAndSeveralConfirmedCategoriesCountAsOneCase() {
		UUID caseId = sink.open(flood(message(), List.of()));
		findings.propose(input(caseId, List.of(Category.PROFANITY, Category.HARASSMENT), 1));
		assertTrue(history().isEmpty());
		findings.confirm(input(caseId, List.of(Category.PROFANITY, Category.HARASSMENT), 1));
		assertEquals(List.of(caseId), history());
		assertEquals(4, count("guard.findings"));
	}

	@Test
	void confirmedNonViolationAndNeedsReviewAreNotViolationHistory() {
		UUID normal = sink.open(flood(message(), List.of()));
		findings.confirm(new FindingInput(normal, List.of(Category.FLOOD), Verdict.NO_VIOLATION,
				"test-policy", "evidence-fixture", 1, "normal-decision", "Normal repetition"));
		UUID review = sink.open(flood(message(), List.of()));
		findings.confirm(new FindingInput(review, List.of(Category.FLOOD), Verdict.NEEDS_REVIEW,
				"test-policy", "evidence-fixture", 1, "review-decision", "Insufficient evidence"));
		assertTrue(history().isEmpty());
		assertEquals(2, count("guard.findings"));
	}

	@Test
	void revocationKeepsEvidenceAndAuditAndOnlyRemovesTheRevokedFinding() {
		UUID caseId = sink.open(flood(message(), List.of()));
		List<UUID> ids = findings.confirm(input(caseId, List.of(Category.PROFANITY, Category.HARASSMENT), 1));
		findings.revoke(ids.getFirst(), "operator-decision-1", "Incorrect profanity finding");
		assertEquals(List.of(caseId), history());
		findings.revoke(ids.getLast(), "operator-decision-2", "Incorrect harassment finding");
		assertTrue(history().isEmpty());
		assertEquals(2, count("guard.findings"));
		assertEquals(4, count("guard.finding_events"));
		assertEquals(List.of("evidence-fixture", "evidence-fixture"),
				jdbc.queryForList("SELECT evidence_reference FROM guard.findings", String.class));
		assertThrows(InvalidContractException.class,
				() -> findings.revoke(ids.getFirst(), "duplicate", "Already revoked"));
	}

	@Test
	void staleEvidenceCannotBeConfirmedAndBatchConfirmationIsAtomic() {
		UUID first = message();
		UUID caseId = sink.open(flood(first, List.of()));
		sink.open(flood(message(), List.of(first)));
		assertThrows(InvalidContractException.class,
				() -> findings.confirm(input(caseId, List.of(Category.FLOOD), 1)));
		findings.confirm(input(caseId, List.of(Category.PROFANITY), 2));
		assertThrows(DataIntegrityViolationException.class,
				() -> findings.confirm(input(caseId, List.of(Category.HARASSMENT, Category.PROFANITY), 2)));
		assertEquals(1, count("guard.findings"));
		assertEquals(1, count("guard.finding_events"));
	}

	@Test
	void replayCannotChangeTheCaseScopeAndPassCannotCreateACase() {
		UUID id = message();
		sink.open(flood(id, List.of()));
		assertThrows(InvalidContractException.class, () -> sink.open(new CandidateRequest(
				UUID.randomUUID(), USER, id, FilterResult.FLOOD, List.of("REPEATED_TEXT"),
				"norm-v1+dict-v2", NOW, List.of())));
		assertThrows(InvalidContractException.class, () -> sink.open(new CandidateRequest(
				CHANNEL, USER, message(), FilterResult.PASS, List.of(), "norm-v1+dict-v2", NOW, List.of())));
	}

	@Test
	void invalidRelatedMessagesDoNotCreateCases() {
		UUID id = message();
		assertThrows(InvalidContractException.class, () -> sink.open(flood(id, List.of(id))));
		UUID related = message();
		assertThrows(InvalidContractException.class, () -> sink.open(flood(id, List.of(related, related))));
		assertEquals(0, count("guard.cases"));
	}

	private FindingInput input(UUID caseId, List<Category> categories, int version) {
		return new FindingInput(caseId, categories, Verdict.VIOLATION, "test-policy", "evidence-fixture",
				version, "decision-fixture", "Test evidence assessment");
	}

	private List<UUID> history() {
		return findings.confirmedCaseIds(CHANNEL, USER, NOW.minusSeconds(86400), NOW.plusSeconds(1));
	}

	private CandidateRequest flood(UUID messageId, List<UUID> related) {
		return new CandidateRequest(CHANNEL, USER, messageId, FilterResult.FLOOD,
				List.of("REPEATED_TEXT"), "norm-v1+dict-v2", NOW, related);
	}

	private UUID message() {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO platform.messages(id, channel_id, author_id, client_message_id,
					text, normalized_text, filter_result, visibility, sent_at)
				VALUES (?, ?, ?, ?, 'test', 'test', 'FLOOD', 'HELD', ?)
				""", id, CHANNEL, USER, UUID.randomUUID(), Timestamp.from(NOW));
		return id;
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}
}
