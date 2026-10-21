package com.lifeguards.liveguard.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.platform.port.LivePlatformPort;
import com.lifeguards.liveguard.support.CaseFixtures;

/** 가림 원인별 해제(E-2). 한 메시지를 가린 원인이 여럿이면 자기 원인만 풀리고, 마지막 원인이 풀릴 때 PUBLIC이 된다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class HoldReasonReleaseTest {

	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");

	@Autowired
	private LivePlatformPort platform;
	@Autowired
	private JdbcTemplate jdbc;

	private UUID channel;
	private UUID live;
	private UUID caseA;
	private UUID caseB;
	private UUID message;

	@BeforeEach
	void setUp() {
		channel = UUID.randomUUID();
		live = UUID.randomUUID();
		caseA = UUID.randomUUID();
		caseB = UUID.randomUUID();
		message = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.channels (id, name) VALUES (?, 'hold-reason-test')", channel);
		jdbc.update("INSERT INTO platform.channel_members (channel_id, user_id, member_role) VALUES (?, ?, 'VIEWER')",
				channel, VIEWER);
		jdbc.update("INSERT INTO platform.lives (id, channel_id, started_at) VALUES (?, ?, now())", live, channel);
		CaseFixtures.insertCase(jdbc, caseA, channel, VIEWER);
		CaseFixtures.insertCase(jdbc, caseB, channel, VIEWER);
		// 사건 A가 소유한(case_id) HELD 메시지
		jdbc.update("INSERT INTO platform.messages (id, channel_id, live_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, case_id, sent_at) "
				+ "VALUES (?, ?, ?, ?, ?, 'hold', 'hold', 'HOLD', 'HELD', ?, now())",
				message, channel, live, VIEWER, UUID.randomUUID(), caseA);
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM platform.message_hold_reasons WHERE message_id = ?", message);
		jdbc.update("DELETE FROM platform.messages WHERE id = ?", message);
		jdbc.update("DELETE FROM guard.cases WHERE id IN (?, ?)", caseA, caseB);
		jdbc.update("DELETE FROM platform.lives WHERE id = ?", live);
		jdbc.update("DELETE FROM platform.channel_members WHERE channel_id = ?", channel);
		jdbc.update("DELETE FROM platform.channels WHERE id = ?", channel);
	}

	@Test
	void releasingTheOnlyReasonRestoresTheMessage() {
		addReason(caseA, "CONTEXT_REVIEW");

		platform.visibility(caseA, "PUBLIC");

		assertEquals("PUBLIC", visibility());
		assertEquals(2, version());
		assertNotNull(releasedAt(caseA));
	}

	@Test
	void releasingOneCaseKeepsTheMessageHeldWhileAnotherReasonRemains() {
		addReason(caseA, "CONTEXT_REVIEW");
		addReason(caseB, "FLOOD_REVIEW");

		platform.visibility(caseA, "PUBLIC");

		assertEquals("HELD", visibility());
		assertEquals(1, version());
		assertNotNull(releasedAt(caseA));
		assertNull(releasedAt(caseB));
	}

	@Test
	void releasingTheLastReasonOfAnotherCaseRestoresTheMessage() {
		addReason(caseA, "CONTEXT_REVIEW");
		addReason(caseB, "FLOOD_REVIEW");
		platform.visibility(caseA, "PUBLIC");

		// 메시지의 case_id는 A지만, 남은 원인은 B의 것이다. B가 풀면 마지막 원인이 풀린 것이다
		platform.visibility(caseB, "PUBLIC");

		assertEquals("PUBLIC", visibility());
		assertEquals(2, version());
		assertNotNull(releasedAt(caseB));
	}

	private void addReason(UUID caseId, String reason) {
		jdbc.update("INSERT INTO platform.message_hold_reasons (message_id, case_id, reason, created_at) "
				+ "VALUES (?, ?, ?, now())", message, caseId, reason);
	}

	private String visibility() {
		return jdbc.queryForObject("SELECT visibility FROM platform.messages WHERE id = ?", String.class, message);
	}

	private int version() {
		return jdbc.queryForObject("SELECT version FROM platform.messages WHERE id = ?", Integer.class, message);
	}

	private Object releasedAt(UUID caseId) {
		return jdbc.queryForObject("SELECT released_at FROM platform.message_hold_reasons "
				+ "WHERE message_id = ? AND case_id = ?", Object.class, message, caseId);
	}

}
