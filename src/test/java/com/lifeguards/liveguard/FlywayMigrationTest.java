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

		assertEquals(List.of("100", "101", "200"), versions);
	}

	@Test
	void createsSchemasExtensionAndPlatformTables() {
		assertEquals(2, jdbc.queryForObject(
				"SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('platform', 'guard')",
				Integer.class));
		assertEquals(1, jdbc.queryForObject(
				"SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class));
		assertEquals(List.of("channel_members", "channels", "messages", "users"), jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'platform' ORDER BY table_name",
				String.class));
	}

	@Test
	void createsGuardPersistenceTables() {
		assertEquals(List.of("candidates", "case_messages", "cases", "finding_events", "findings"), jdbc.queryForList(
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
