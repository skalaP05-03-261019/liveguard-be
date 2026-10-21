package com.lifeguards.liveguard.platform;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static com.lifeguards.liveguard.support.LoginSupport.withXsrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
		jdbc.update("INSERT INTO platform.messages (channel_id, author_id, client_message_id, text, normalized_text, "
				+ "filter_result, visibility, sent_at) VALUES (?, ?, ?, '응원해요', '응원해요', 'PASS', 'PUBLIC', ?)",
				CHANNEL_B, VIEWER, UUID.randomUUID(), Timestamp.from(Instant.now()));

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
	void holdMessageIsHeldAndHiddenFromOtherViewersHistory() throws Exception {
		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), D07, login(mockMvc, "moderator")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.visibility").value("HELD"))
				.andExpect(jsonPath("$.data.text").value(D07));

		assertEquals("HOLD", jdbc.queryForObject("SELECT filter_result FROM platform.messages", String.class));
		assertEquals(Visibility.HELD, recorder.events.getLast().chat().visibility());
		String viewerHistory = mockMvc.perform(get("/api/channels/" + CHANNEL_A + "/messages")
				.session(login(mockMvc, "viewer")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertFalse(viewerHistory.contains("evil.test"));
	}

	@Test
	void reviewIsStoredButStaysPublic() throws Exception {
		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), "무대 미쳤다ㅋㅋ", login(mockMvc, "viewer")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.visibility").value("PUBLIC"));

		assertEquals("REVIEW", jdbc.queryForObject("SELECT filter_result FROM platform.messages", String.class));
		assertEquals(Visibility.PUBLIC, recorder.events.getLast().chat().visibility());
	}

	@Test
	void certainProfanityIsHoldHeldAndHiddenFromOtherViewers() throws Exception {
		UUID otherViewer = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.users (id, username, password_hash, nickname, role) SELECT ?, "
				+ "'profanity-viewer2', password_hash, '시청자2', 'VIEWER' FROM platform.users WHERE username = 'viewer'",
				otherViewer);
		jdbc.update("INSERT INTO platform.channel_members (channel_id, user_id, member_role) VALUES (?, ?, 'VIEWER')",
				CHANNEL_A, otherViewer);
		try {
			mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), "씨발 진짜", login(mockMvc, "viewer")))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.data.visibility").value("HELD"))
					.andExpect(jsonPath("$.data.text").value("씨발 진짜"));

			assertEquals("HOLD", jdbc.queryForObject("SELECT filter_result FROM platform.messages", String.class));
			assertEquals("HELD", jdbc.queryForObject("SELECT visibility FROM platform.messages", String.class));
			assertEquals(Visibility.HELD, recorder.events.getLast().chat().visibility());
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
	void ambiguousAttackTermIsStillReviewAndPublic() throws Exception {
		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), "무대 미쳤다ㅋㅋ", login(mockMvc, "viewer")))
				.andExpect(jsonPath("$.data.visibility").value("PUBLIC"));

		assertEquals("REVIEW", jdbc.queryForObject("SELECT filter_result FROM platform.messages", String.class));
	}

	@Test
	void repeatedCertainProfanityStaysHoldNotFlood() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), "시발", viewer))
					.andExpect(jsonPath("$.data.visibility").value("HELD"));
		}

		assertEquals(List.of("HOLD", "HOLD", "HOLD"), jdbc.queryForList(
				"SELECT filter_result FROM platform.messages ORDER BY sent_at, id", String.class));
	}

	@Test
	void officialHostsComeFromTheChannel() throws Exception {
		String text = "[공식] 이벤트는 https://live-a.example.com/event 에서 로그인 후 참여하세요";
		MockHttpSession viewer = login(mockMvc, "viewer");

		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), text, viewer))
				.andExpect(jsonPath("$.data.visibility").value("PUBLIC"));
		// 채널 B의 공식 호스트에는 live-a.example.com이 없다
		mockMvc.perform(send(CHANNEL_B, UUID.randomUUID(), text, viewer))
				.andExpect(jsonPath("$.data.visibility").value("HELD"));

		assertEquals(List.of("PASS", "HOLD"),
				jdbc.queryForList("SELECT filter_result FROM platform.messages ORDER BY sent_at", String.class));
	}

	private String history(String username) throws Exception {
		return mockMvc.perform(get("/api/channels/" + CHANNEL_A + "/messages").session(login(mockMvc, username)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private void insert(UUID author, String text, Instant sentAt) {
		jdbc.update("INSERT INTO platform.messages (channel_id, author_id, client_message_id, text, normalized_text, "
				+ "filter_result, visibility, sent_at) VALUES (?, ?, ?, ?, ?, 'PASS', 'PUBLIC', ?)",
				CHANNEL_A, author, UUID.randomUUID(), text, TextNormalizer.normalize(text), Timestamp.from(sentAt));
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
