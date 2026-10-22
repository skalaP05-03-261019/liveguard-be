package com.lifeguards.liveguard.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
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
import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.platform.port.LiveMessageQuery;
import com.lifeguards.liveguard.platform.port.MessageView;

/** 사건 증거 메시지를 방송 범위 안에서 읽는 포트(이슈 #25 §4). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LiveMessageQueryTest {

	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID MODERATOR = UUID.fromString("10000000-0000-4000-8000-000000000002");
	private static final Instant T0 = Instant.parse("2026-10-20T00:00:00Z");

	@Autowired
	private LiveMessageQuery query;
	@Autowired
	private JdbcTemplate jdbc;

	private UUID channel;
	private UUID live;
	private UUID otherLive;

	@BeforeEach
	void setUp() {
		channel = UUID.randomUUID();
		live = UUID.randomUUID();
		otherLive = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.channels (id, name) VALUES (?, 'message-view-test')", channel);
		// 채널당 진행 중 방송은 하나뿐이라 끝난 방송만 만든다
		for (UUID id : List.of(live, otherLive)) {
			jdbc.update("INSERT INTO platform.lives (id, channel_id, started_at, ended_at) VALUES (?, ?, ?, ?)",
					id, channel, Timestamp.from(T0), Timestamp.from(T0.plusSeconds(3600)));
		}
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM platform.messages WHERE channel_id = ?", channel);
		jdbc.update("DELETE FROM platform.lives WHERE channel_id = ?", channel);
		jdbc.update("DELETE FROM platform.channels WHERE id = ?", channel);
	}

	@Test
	void returnsRequestedMessagesInRequestOrderWithCurrentVisibilityAndVersion() {
		UUID a = insert(live, VIEWER, "첫 메시지", "PUBLIC", 1, 1);
		UUID b = insert(live, MODERATOR, "가려진 메시지", "HELD", 1, 2);
		UUID c = insert(live, VIEWER, "숨겨진 메시지", "HIDDEN", 3, 3);

		List<MessageView> views = query.messages(live, List.of(c, a, b));

		assertEquals(List.of(c, a, b), views.stream().map(MessageView::messageId).toList());
		assertEquals(List.of(Visibility.HIDDEN, Visibility.PUBLIC, Visibility.HELD),
				views.stream().map(MessageView::visibility).toList());
		assertEquals(List.of(3, 1, 1), views.stream().map(MessageView::version).toList());
		assertEquals(List.of("숨겨진 메시지", "첫 메시지", "가려진 메시지"),
				views.stream().map(MessageView::maskedText).toList());
		assertEquals(List.of(VIEWER, VIEWER, MODERATOR), views.stream().map(MessageView::authorId).toList());
		assertEquals(T0.plusSeconds(3), views.get(0).sentAt());
	}

	@Test
	void givesChannelFilterResultAndNormalizedTextHashButNotTheNormalizedText() throws Exception {
		UUID id = insert(live, VIEWER, "도배 문장", "HELD", 1, 1);

		MessageView view = query.messages(live, List.of(id)).getFirst();

		assertEquals(channel, view.channelId());
		assertEquals(FilterResult.HOLD, view.filterResult());
		// guard의 flood_marks.text_hash와 같은 값이어야 도배 표식과 바로 비교할 수 있다
		assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest("도배 문장".getBytes(StandardCharsets.UTF_8))), view.normalizedTextHash());
	}

	@Test
	void masksPersonalDataEvenForHeldMessages() {
		UUID id = insert(live, VIEWER, "연락 010-1234-5678 로 주세요 me@example.com", "HELD", 1, 1);

		String text = query.messages(live, List.of(id)).getFirst().maskedText();

		assertFalse(text.contains("010-1234-5678"));
		assertFalse(text.contains("me@example.com"));
		assertTrue(text.contains("[PHONE]"));
		assertTrue(text.contains("[EMAIL]"));
	}

	@Test
	void rejectsMessagesOfAnotherLiveWithoutLeakingTheText() {
		UUID mine = insert(live, VIEWER, "내 방송", "PUBLIC", 1, 1);
		UUID foreign = insert(otherLive, VIEWER, "다른 방송 비밀 문장", "PUBLIC", 1, 2);

		ApiException ex = assertThrows(ApiException.class, () -> query.messages(live, List.of(mine, foreign)));

		assertEquals(ErrorCode.VALIDATION_FAILED, ex.code());
		assertFalse(ex.getMessage().contains("비밀 문장"));
	}

	@Test
	void rejectsMessagesFromBeforeTheLiveLifecycle() {
		UUID legacy = insert(null, VIEWER, "방송 도입 전", "PUBLIC", 1, 1);

		assertEquals(ErrorCode.VALIDATION_FAILED,
				assertThrows(ApiException.class, () -> query.messages(live, List.of(legacy))).code());
	}

	@Test
	void failsInsteadOfSilentlyOmittingAnUnknownMessage() {
		UUID known = insert(live, VIEWER, "있는 메시지", "PUBLIC", 1, 1);

		assertEquals(ErrorCode.NOT_FOUND,
				assertThrows(ApiException.class, () -> query.messages(live, List.of(known, UUID.randomUUID()))).code());
	}

	@Test
	void anotherLiveWinsOverAnUnknownMessage() {
		UUID foreign = insert(otherLive, VIEWER, "다른 방송", "PUBLIC", 1, 1);

		assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class,
				() -> query.messages(live, List.of(foreign, UUID.randomUUID()))).code());
	}

	@Test
	void returnsEmptyForNoIdsAndEachDuplicateOnce() {
		UUID id = insert(live, VIEWER, "한 번만", "PUBLIC", 1, 1);

		assertEquals(List.of(), query.messages(live, List.of()));
		assertEquals(1, query.messages(live, List.of(id, id)).size());
	}

	private UUID insert(UUID liveId, UUID author, String text, String visibility, int version, int secondsAfterT0) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.messages (id, channel_id, live_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, version, sent_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				id, channel, liveId, author, UUID.randomUUID(), text, text,
				"PUBLIC".equals(visibility) ? "PASS" : "HOLD", visibility, version,
				Timestamp.from(T0.plusSeconds(secondsAfterT0)));
		return id;
	}

}
