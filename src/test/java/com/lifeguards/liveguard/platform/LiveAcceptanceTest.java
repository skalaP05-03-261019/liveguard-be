package com.lifeguards.liveguard.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.enums.Role;

/**
 * 정책 원본 PROJECT.md 수용 기준 중 채팅 전송 경로(B1)에 걸린 항목을 실제 guard까지 이어서 확인한다.
 * AC-01 PASS, AC-02 인용 속 욕설, AC-03 링크 금지, AC-06 숫자 나열, AC-10 TIMEOUT 중 전송.
 */
@SpringBootTest(properties = { "LIVEGUARD_POLICY_VERSION=v3.1", "LIVEGUARD_TBD_FIXTURE_ENABLED=true" })
@Import(TestcontainersConfiguration.class)
@Transactional
class LiveAcceptanceTest {

	@Autowired
	private LiveService lives;
	@Autowired
	private JdbcTemplate db;

	private UUID channel;
	private UUID live;
	private AuthenticatedUser viewer;

	record User(UUID userId, String nickname, Role role) implements AuthenticatedUser {
	}

	@BeforeEach
	void setUp() {
		channel = UUID.randomUUID();
		UUID viewerId = UUID.randomUUID();
		UUID moderatorId = UUID.randomUUID();
		db.update("INSERT INTO platform.channels(id,name) VALUES(?,'acceptance')", channel);
		for (UUID id : List.of(viewerId, moderatorId)) {
			db.update("INSERT INTO platform.users(id,username,password_hash,nickname,role) VALUES(?,?,'unused','test','VIEWER')",
					id, id.toString());
			db.update("INSERT INTO platform.channel_members(channel_id,user_id,member_role) VALUES(?,?,?)", channel, id,
					id.equals(moderatorId) ? "MODERATOR" : "VIEWER");
		}
		viewer = new User(viewerId, "viewer", Role.VIEWER);
		live = lives.start(channel, new User(moderatorId, "moderator", Role.MODERATOR)).liveId();
	}

	@Test
	void ac01PlainChatIsPublicWithoutACase() {
		Map<String, Object> sent = send("오늘 무대 좋았어요");

		assertEquals("PUBLIC", sent.get("visibility"));
		assertNull(caseOf(sent));
		assertEquals(0, count("SELECT count(*) FROM guard.agent_task_outbox"));
	}

	@Test
	void ac02ProfanityInsideAQuoteIsHiddenWithoutATimeoutOnTheFirstOffence() {
		Map<String, Object> sent = send("\"시발\"이라고 쓰지 마세요");

		assertEquals("HIDDEN", sent.get("visibility"));
		assertEquals("PROFANITY_INCLUDED", sent.get("moderationReasonCode"));
		assertEquals(0, count("SELECT count(*) FROM guard.live_timeouts WHERE live_id=?", live));
		// 첫 PROFANITY는 TIMEOUT이 없으므로 다음 채팅은 그대로 보낼 수 있다
		assertEquals("PUBLIC", send("다음 채팅").get("visibility"));
	}

	@Test
	void ac03AnyLinkIsBlockedAndTheFirstPhishingTimeoutIs600Seconds() {
		Map<String, Object> sent = send("공식 이벤트는 https://live-a.example.com/event 에서 확인하세요");

		assertEquals("HIDDEN", sent.get("visibility"));
		assertEquals("LINK_NOT_ALLOWED", sent.get("moderationReasonCode"));
		assertEquals(600, count("SELECT round(extract(epoch FROM t.expires_at - a.created_at))::int FROM guard.live_timeouts t "
				+ "JOIN guard.live_actions a ON a.id=t.action_id WHERE t.live_id=?", live));
	}

	@Test
	void ac06NumbersAloneArePublicWithoutACase() {
		for (String text : List.of("12345678901234", "1004", "2026-10-20 21:30", "1,000,000원")) {
			Map<String, Object> sent = send(text);
			assertEquals("PUBLIC", sent.get("visibility"), text);
			assertNull(caseOf(sent), text);
		}
	}

	@Test
	void ac10SendingDuringATimeoutIsBlockedAndNotCountedAsANewChatOrCase() {
		send("여기 https://evil.test 접속");
		int messages = count("SELECT count(*) FROM platform.messages WHERE live_id=?", live);
		int cases = count("SELECT count(*) FROM guard.cases WHERE live_id=?", live);

		ApiException blocked = assertThrows(ApiException.class, () -> send("시발"));

		assertEquals(ErrorCode.TIMED_OUT, blocked.code());
		assertEquals(messages, count("SELECT count(*) FROM platform.messages WHERE live_id=?", live));
		assertEquals(cases, count("SELECT count(*) FROM guard.cases WHERE live_id=?", live));
	}

	private Map<String, Object> send(String text) {
		return lives.send(live, viewer, new SendMessageRequest(UUID.randomUUID(), text));
	}

	private UUID caseOf(Map<String, Object> sent) {
		return db.queryForObject("SELECT case_id FROM platform.messages WHERE id=?", UUID.class, sent.get("messageId"));
	}

	private int count(String sql, Object... args) {
		return db.queryForObject(sql, Integer.class, args);
	}

}
