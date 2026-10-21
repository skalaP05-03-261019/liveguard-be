package com.lifeguards.liveguard.platform;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static com.lifeguards.liveguard.support.LoginSupport.withXsrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.support.CaseFixtures;
import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.lifeguards.liveguard.filter.TextNormalizer;

/** 전송 경로에 연결된 1차 필터: 저장되는 filter_result·visibility, 응답, 이벤트, 이력 공개 범위. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, MessageControllerTest.EventRecorder.class })
class MessageFilteringTest {

	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID CHANNEL_B = UUID.fromString("20000000-0000-4000-8000-000000000002");
	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final String D07 = "주의! 공식 이벤트 당첨, http://live-a-event.evil.test 에서 로그인하세요";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private MessageControllerTest.EventRecorder recorder;

	@BeforeEach
	void clearEvents() {
		recorder.events.clear();
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM platform.messages");
		CaseFixtures.deleteCases(jdbc);
	}

	@Test
	void thirdSameTextWithinTenSecondsIsFloodAndHeld() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		List<String> visibilities = new ArrayList<>();
		for (String text : new String[] { "도배 문장", "도배   문장", "도배 문장!!!", "도배 문장" }) {
			// "도배   문장"은 정규화하면 "도배 문장"과 같고, "도배 문장!!!"은 "도배 문장!!"이라 다른 문장이다.
			// 따라서 같은 문장의 세 번째인 마지막 메시지만 FLOOD
			String body = mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), text, viewer))
					.andExpect(status().isCreated())
					.andReturn().getResponse().getContentAsString();
			visibilities.add(JsonPath.read(body, "$.data.visibility"));
		}

		assertEquals(List.of("PUBLIC", "PUBLIC", "PUBLIC", "HELD"), visibilities);
		assertEquals(List.of("PASS", "PASS", "PASS", "FLOOD"), jdbc.queryForList(
				"SELECT filter_result FROM platform.messages ORDER BY sent_at", String.class));
		assertEquals(Visibility.HELD, recorder.events.getLast().chat().visibility());
	}

	@Test
	void sameTextOlderThanTenSecondsDoesNotCount() throws Exception {
		String text = "오래된 문장";
		insert(VIEWER, text, Instant.now().minusSeconds(11));
		insert(VIEWER, text, Instant.now().minusSeconds(12));

		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), text, login(mockMvc, "viewer")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.visibility").value("PUBLIC"));

		insert(VIEWER, text, Instant.now().minusSeconds(2));
		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), text, login(mockMvc, "viewer")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.visibility").value("HELD"));
	}

	@Test
	void sameTextFromOtherUsersOrChannelsDoesNotCount() throws Exception {
		UUID moderator = UUID.fromString("10000000-0000-4000-8000-000000000002");
		insert(moderator, "응원해요", Instant.now().minusSeconds(1));
		insert(moderator, "응원해요", Instant.now().minusSeconds(1));
		jdbc.update("INSERT INTO platform.messages (channel_id, live_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, sent_at) VALUES (?, (SELECT id FROM platform.lives WHERE channel_id = ? AND ended_at IS NULL), ?, ?, '응원해요', "
				+ "'응원해요', 'PASS', 'PUBLIC', ?)",
				CHANNEL_B, CHANNEL_B, VIEWER, UUID.randomUUID(), Timestamp.from(Instant.now()));

		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), "응원해요", login(mockMvc, "viewer")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.visibility").value("PUBLIC"));
	}

	@Test
	void replaysDoNotIncreaseTheFloodCount() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		UUID first = UUID.randomUUID();
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(send(CHANNEL_A, first, "재전송", viewer)).andExpect(status().isCreated());
		}

		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), "재전송", viewer))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.visibility").value("PUBLIC"));
		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM platform.messages", Integer.class));
	}

	@Test
	void linkMessageIsHiddenAndNotInOtherViewersHistory() throws Exception {
		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), D07, login(mockMvc, "moderator")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.visibility").value("HIDDEN"))
				.andExpect(jsonPath("$.data.text").value(D07));

		assertEquals("HOLD", jdbc.queryForObject("SELECT filter_result FROM platform.messages", String.class));
		assertEquals(Visibility.HIDDEN, recorder.events.getLast().chat().visibility());
		String viewerHistory = mockMvc.perform(get("/api/channels/" + CHANNEL_A + "/messages")
				.session(login(mockMvc, "viewer")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertFalse(viewerHistory.contains("evil.test"));
	}

	@Test
	void attackTermIsContextReviewHeldNotReview() throws Exception {
		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), "무대 미쳤다ㅋㅋ", login(mockMvc, "viewer")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.visibility").value("HELD"));

		assertEquals("HOLD", jdbc.queryForObject("SELECT filter_result FROM platform.messages", String.class));
		assertEquals(Visibility.HELD, recorder.events.getLast().chat().visibility());
	}

	@Test
	void certainProfanityIsHiddenOnArrivalAndNotShownToOtherViewers() throws Exception {
		UUID otherViewer = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.users (id, username, password_hash, nickname, role) SELECT ?, "
				+ "'profanity-viewer2', password_hash, '시청자2', 'VIEWER' FROM platform.users WHERE username = 'viewer'",
				otherViewer);
		jdbc.update("INSERT INTO platform.channel_members (channel_id, user_id, member_role) VALUES (?, ?, 'VIEWER')",
				CHANNEL_A, otherViewer);
		try {
			mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), "씨발 진짜", login(mockMvc, "viewer")))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.data.visibility").value("HIDDEN"))
					.andExpect(jsonPath("$.data.text").value("씨발 진짜"));

			assertEquals("HOLD", jdbc.queryForObject("SELECT filter_result FROM platform.messages", String.class));
			assertEquals("HIDDEN", jdbc.queryForObject("SELECT visibility FROM platform.messages", String.class));
			assertEquals(Visibility.HIDDEN, recorder.events.getLast().chat().visibility());
			assertTrue(history("moderator").contains("씨발 진짜"), "담당 운영자는 원문을 본다");
			assertTrue(history("viewer").contains("씨발 진짜"), "작성자는 원문을 본다");
			assertFalse(history("profanity-viewer2").contains("씨발"), "다른 시청자 이력에는 없다");
		} finally {
			jdbc.update("DELETE FROM platform.messages");
			jdbc.update("DELETE FROM platform.channel_members WHERE user_id = ?", otherViewer);
			jdbc.update("DELETE FROM platform.users WHERE id = ?", otherViewer);
		}
	}

	@Test
	void repeatedCertainProfanityStaysHoldNotFlood() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), "시발", viewer))
					.andExpect(jsonPath("$.data.visibility").value("HIDDEN"));
		}

		assertEquals(List.of("HOLD", "HOLD", "HOLD"), jdbc.queryForList(
				"SELECT filter_result FROM platform.messages ORDER BY sent_at, id", String.class));
	}

	@Test
	void officialLinkIsHiddenOnEveryChannel() throws Exception {
		String text = "[공식] 이벤트는 https://live-a.example.com/event 에서 로그인 후 참여하세요";
		MockHttpSession viewer = login(mockMvc, "viewer");

		// 채널 A의 공식 호스트라도 링크는 예외 없이 막는다 (v3.1). 채널 B도 같다
		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), text, viewer))
				.andExpect(jsonPath("$.data.visibility").value("HIDDEN"));
		mockMvc.perform(send(CHANNEL_B, UUID.randomUUID(), text, viewer))
				.andExpect(jsonPath("$.data.visibility").value("HIDDEN"));

		assertEquals(List.of("HOLD", "HOLD"),
				jdbc.queryForList("SELECT filter_result FROM platform.messages ORDER BY sent_at", String.class));
	}

	@Test
	void authorResponseCarriesTheModerationReasonCodeForEachRoute() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");

		assertEquals("PROFANITY_INCLUDED", reasonOf(sendOk("씨발 진짜", viewer)));
		assertEquals("LINK_NOT_ALLOWED", reasonOf(sendOk("여기 https://evil.test 접속", viewer)));
		assertEquals("CONTEXT_PENDING", reasonOf(sendOk("무대 미쳤다ㅋㅋ", viewer)));
		assertNull(reasonOf(sendOk("오늘 무대 좋았어요", viewer)));
		assertNull(reasonOf(sendOk("도배 문장", viewer)));
		assertNull(reasonOf(sendOk("도배 문장", viewer)));
		assertEquals("FLOOD_REPEATED", reasonOf(sendOk("도배 문장", viewer)));

		assertEquals(List.of("PROFANITY_INCLUDED", "LINK_NOT_ALLOWED", "CONTEXT_PENDING", "FLOOD_REPEATED"),
				jdbc.queryForList("SELECT moderation_reason_code FROM platform.messages "
						+ "WHERE moderation_reason_code IS NOT NULL ORDER BY sent_at, id", String.class));
	}

	@Test
	void replayReturnsTheSameModerationReasonCode() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		UUID clientMessageId = UUID.randomUUID();

		String first = mockMvc.perform(send(CHANNEL_A, clientMessageId, "씨발 진짜", viewer))
				.andReturn().getResponse().getContentAsString();
		String replay = mockMvc.perform(send(CHANNEL_A, clientMessageId, "씨발 진짜", viewer))
				.andReturn().getResponse().getContentAsString();

		assertEquals("PROFANITY_INCLUDED", reasonOf(first));
		assertEquals("PROFANITY_INCLUDED", reasonOf(replay));
	}

	@Test
	void historyShowsTheReasonOnlyToTheAuthorWhileTheMessageIsConcealed() throws Exception {
		String sent = sendOk("씨발 진짜", login(mockMvc, "viewer"));
		String messageId = JsonPath.read(sent, "$.data.messageId");

		assertEquals(List.of("PROFANITY_INCLUDED"), reasonsInHistory("viewer", messageId));
		// 담당 운영자는 원문을 보지만 사유 코드는 작성자 응답에만 있다
		assertNull(reasonsInHistory("moderator", messageId).getFirst());

		// 복구되어 PUBLIC이 되면 작성자에게도 사유가 없다
		jdbc.update("UPDATE platform.messages SET visibility = 'PUBLIC' WHERE id = ?::uuid", messageId);
		assertNull(reasonsInHistory("viewer", messageId).getFirst());
	}

	@Test
	void concealedMessageWithoutStoredReasonStillReadsAsNull() throws Exception {
		// 사유 컬럼 도입 전에 저장된 가려진 메시지
		jdbc.update("INSERT INTO platform.messages (channel_id, live_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, sent_at) VALUES (?, (SELECT id FROM platform.lives "
				+ "WHERE channel_id = ? AND ended_at IS NULL), ?, ?, '예전 메시지', '예전 메시지', 'HOLD', 'HELD', ?)",
				CHANNEL_A, CHANNEL_A, VIEWER, UUID.randomUUID(), Timestamp.from(Instant.now()));

		String body = history("viewer");

		assertEquals(1, JsonPath.<List<Object>>read(body, "$.data.items").size());
		assertNull(JsonPath.read(body, "$.data.items[0].moderationReasonCode"));
	}

	private String sendOk(String text, MockHttpSession session) throws Exception {
		return mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), text, session))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	private static String reasonOf(String responseBody) {
		return JsonPath.read(responseBody, "$.data.moderationReasonCode");
	}

	private List<String> reasonsInHistory(String username, String messageId) throws Exception {
		return JsonPath.read(history(username), "$.data.items[?(@.messageId=='" + messageId + "')].moderationReasonCode");
	}

	private String history(String username) throws Exception {
		return mockMvc.perform(get("/api/channels/" + CHANNEL_A + "/messages").session(login(mockMvc, username)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private void insert(UUID author, String text, Instant sentAt) {
		// 도배는 같은 방송 범위라 시드 채널의 진행 중 방송에 넣는다
		jdbc.update("INSERT INTO platform.messages (channel_id, live_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, sent_at) VALUES (?, (SELECT id FROM platform.lives WHERE channel_id = ? AND ended_at IS NULL), ?, ?, ?, ?, "
				+ "'PASS', 'PUBLIC', ?)",
				CHANNEL_A, CHANNEL_A, author, UUID.randomUUID(), text, TextNormalizer.normalize(text),
				Timestamp.from(sentAt));
	}

	private MockHttpServletRequestBuilder send(UUID channelId, UUID clientMessageId, String text,
			MockHttpSession session) throws Exception {
		String json = "{\"clientMessageId\":\"" + clientMessageId + "\",\"text\":\"" + text.replace("\"", "\\\"")
				+ "\"}";
		return withXsrf(mockMvc, post("/api/channels/" + channelId + "/messages"))
				.session(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json);
	}

}
