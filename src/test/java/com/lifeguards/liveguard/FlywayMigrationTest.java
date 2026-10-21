package com.lifeguards.liveguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 기본 프로파일(시드 없음)에서 적용된 스키마와 제약을 확인한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class FlywayMigrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	private UUID channelId;
	private UUID userId;

	@BeforeEach
	void insertChannelAndUser() {
		channelId = UUID.randomUUID();
		userId = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.channels (id, name) VALUES (?, 'c')", channelId);
		jdbc.update("INSERT INTO platform.users (id, username, password_hash, nickname, role) "
				+ "VALUES (?, 'u', 'h', 'n', 'VIEWER')", userId);
	}

	@Test
	void appliesPlatformAndGuardMigrations() {
		List<String> versions = jdbc.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
				String.class);

		assertEquals(List.of("100", "101", "102", "200", "201", "202"), versions);
	}

	@Test
	void createsSchemasExtensionAndPlatformTables() {
		assertEquals(2, jdbc.queryForObject(
				"SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('platform', 'guard')",
				Integer.class));
		assertEquals(1, jdbc.queryForObject(
				"SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class));
		assertEquals(List.of("channel_members", "channels", "lives", "message_hold_reasons", "messages", "users"), jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'platform' ORDER BY table_name",
				String.class));
	}

	@Test
	void createsGuardPersistenceTables() {
		assertEquals(List.of("agent_task_outbox", "candidates", "case_messages", "cases", "decisions", "finding_events", "findings", "live_actions", "live_case_state", "live_kicks", "live_reservations", "live_timeouts", "reviews"),
				jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'guard' ORDER BY table_name",
				String.class));
	}

	@Test
	void defaultProfileHasNoSeedData() {
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform.users WHERE id <> ?", Integer.class, userId));
	}

	@Test
	void createsMessageIndexes() {
		List<String> definitions = jdbc.queryForList(
				"SELECT indexdef FROM pg_indexes WHERE schemaname = 'platform' AND tablename = 'messages'",
				String.class);

		assertTrue(definitions.stream().anyMatch(d -> d.contains("(channel_id, sent_at DESC)")));
		assertTrue(definitions.stream()
				.anyMatch(d -> d.contains("(channel_id, author_id, normalized_text, sent_at)")));
		assertTrue(definitions.stream()
				.anyMatch(d -> d.contains("UNIQUE") && d.contains("(channel_id, author_id, client_message_id)")));
	}

	@Test
	void createsLiveScopedMessageIndexes() {
		List<String> definitions = jdbc.queryForList(
				"SELECT indexdef FROM pg_indexes WHERE schemaname = 'platform' AND tablename = 'messages'",
				String.class);

		assertTrue(definitions.stream().anyMatch(d -> d.contains("(live_id, sent_at DESC)")));
		assertTrue(definitions.stream().anyMatch(d -> d.contains("(live_id, author_id, normalized_text, sent_at)")));
	}

	@Test
	void allowsOnlyOneActiveLivePerChannel() {
		insertLive(UUID.randomUUID(), channelId, null);
		insertLive(UUID.randomUUID(), channelId, Instant.parse("2026-01-01T01:00:00Z"));

		assertThrows(DataIntegrityViolationException.class, () -> insertLive(UUID.randomUUID(), channelId, null));
	}

	@Test
	void rejectsLiveThatEndsBeforeItStarts() {
		assertThrows(DataIntegrityViolationException.class,
				() -> insertLive(UUID.randomUUID(), channelId, Instant.parse("2025-12-31T23:59:59Z")));
	}

	@Test
	void messageMayHaveNoLiveOrALiveOfItsOwnChannel() {
		UUID ownLive = UUID.randomUUID();
		insertLive(ownLive, channelId, null);

		insertMessage(UUID.randomUUID(), "no live", "PASS", "PUBLIC");
		insertLiveMessage(ownLive);

		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM platform.messages WHERE live_id IS NULL", Integer.class));
	}

	@Test
	void rejectsMessageWhoseLiveBelongsToAnotherChannel() {
		UUID otherChannel = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.channels (id, name) VALUES (?, 'other')", otherChannel);
		UUID otherLive = UUID.randomUUID();
		insertLive(otherLive, otherChannel, null);

		assertThrows(DataIntegrityViolationException.class, () -> insertLiveMessage(otherLive));
	}

	@Test
	void rejectsMessageForUnknownLive() {
		assertThrows(DataIntegrityViolationException.class, () -> insertLiveMessage(UUID.randomUUID()));
	}

	@Test
	void rejectsDuplicateClientMessageIdForSameAuthorAndChannel() {
		UUID clientMessageId = UUID.randomUUID();
		insertMessage(clientMessageId, "hello", "PASS", "PUBLIC");

		assertThrows(DataIntegrityViolationException.class,
				() -> insertMessage(clientMessageId, "hello", "PASS", "PUBLIC"));
	}

	@Test
	void allowsSameClientMessageIdForDifferentAuthor() {
		UUID clientMessageId = UUID.randomUUID();
		UUID otherUser = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.users (id, username, password_hash, nickname, role) "
				+ "VALUES (?, 'u2', 'h', 'n2', 'VIEWER')", otherUser);
		insertMessage(clientMessageId, "hello", "PASS", "PUBLIC");

		insertMessage(clientMessageId, otherUser, "hello", "PASS", "PUBLIC");
	}

	@Test
	void acceptsTextOfExactlyFiveHundredCodePoints() {
		insertMessage(UUID.randomUUID(), "😀".repeat(500), "PASS", "PUBLIC");
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 501 })
	void rejectsTextOutsideOneToFiveHundredCharacters(int length) {
		assertThrows(DataIntegrityViolationException.class,
				() -> insertMessage(UUID.randomUUID(), "x".repeat(length), "PASS", "PUBLIC"));
	}

	@Test
	void rejectsUnknownFilterResult() {
		assertThrows(DataIntegrityViolationException.class,
				() -> insertMessage(UUID.randomUUID(), "hello", "BLOCK", "PUBLIC"));
	}

	@Test
	void rejectsUnknownVisibility() {
		assertThrows(DataIntegrityViolationException.class,
				() -> insertMessage(UUID.randomUUID(), "hello", "PASS", "DELETED"));
	}

	@Test
	void rejectsUnknownUserRole() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"INSERT INTO platform.users (id, username, password_hash, nickname, role) "
						+ "VALUES (?, 'x', 'h', 'n', 'SUPERUSER')", UUID.randomUUID()));
	}

	@Test
	void rejectsUnknownMemberRole() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"INSERT INTO platform.channel_members (channel_id, user_id, member_role) VALUES (?, ?, 'OWNER')",
				channelId, userId));
	}

	@Test
	void rejectsMessageForUnknownChannelOrAuthor() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
				"INSERT INTO platform.messages (channel_id, author_id, client_message_id, text, normalized_text, "
						+ "filter_result, visibility, sent_at) VALUES (?, ?, ?, 't', 't', 'PASS', 'PUBLIC', ?)",
				UUID.randomUUID(), userId, UUID.randomUUID(), Timestamp.from(Instant.now())));
	}

	@Test
	void messageDefaultsToVersionOne() {
		insertMessage(UUID.randomUUID(), "hello", "PASS", "PUBLIC");

		assertEquals(1, jdbc.queryForObject("SELECT version FROM platform.messages", Integer.class));
	}

	private void insertLive(UUID liveId, UUID channel, Instant endedAt) {
		jdbc.update("INSERT INTO platform.lives (id, channel_id, started_at, ended_at) VALUES (?, ?, ?, ?)",
				liveId, channel, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
				endedAt == null ? null : Timestamp.from(endedAt));
	}

	private void insertLiveMessage(UUID liveId) {
		jdbc.update("INSERT INTO platform.messages (channel_id, live_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, sent_at) VALUES (?, ?, ?, ?, 't', 't', 'PASS', 'PUBLIC', ?)",
				channelId, liveId, userId, UUID.randomUUID(), Timestamp.from(Instant.now()));
	}

	private void insertMessage(UUID clientMessageId, String text, String filterResult, String visibility) {
		insertMessage(clientMessageId, userId, text, filterResult, visibility);
	}

	private void insertMessage(UUID clientMessageId, UUID author, String text, String filterResult,
			String visibility) {
		jdbc.update("INSERT INTO platform.messages (channel_id, author_id, client_message_id, text, normalized_text, "
				+ "filter_result, visibility, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
				channelId, author, clientMessageId, text, text, filterResult, visibility,
				Timestamp.from(Instant.now()));
	}

}
