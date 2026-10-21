package com.lifeguards.liveguard.internal;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.contracts.dto.event.TaskFailedEvent;
import com.lifeguards.liveguard.contracts.dto.event.ToolEvent;
import com.lifeguards.liveguard.platform.port.GuardEventPublisher;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class InternalApiTest {

	private static final String TOKEN = "test-service-token-for-tests-only";
	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID MODERATOR = UUID.fromString("10000000-0000-4000-8000-000000000002");
	private static final Instant T0 = Instant.parse("2026-10-20T00:00:00Z");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoSpyBean
	private GuardEventPublisher guardEvents;

	private UUID caseId;

	@BeforeEach
	void setUp() {
		caseId = UUID.randomUUID();
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM platform.messages");
	}

	// ----- 서비스 토큰 -----

	@Test
	void missingOrWrongTokenIs403() throws Exception {
		insert(VIEWER, "증거", 0, caseId);
		for (MockHttpServletRequestBuilder request : List.of(
				get(context(caseId)),
				get(context(caseId)).header(ServiceTokenFilter.HEADER, "wrong"),
				get(context(caseId)).header(ServiceTokenFilter.HEADER, ""),
				get(context(caseId)).header(ServiceTokenFilter.HEADER, TOKEN + "x"))) {
			mockMvc.perform(request)
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
					.andExpect(jsonPath("$.traceId").isNotEmpty());
		}
	}

	@Test
	void browserSessionWithoutTokenIs403() throws Exception {
		insert(VIEWER, "증거", 0, caseId);

		mockMvc.perform(get(context(caseId)).session(login(mockMvc, "moderator")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void validTokenWorksWithoutSessionOrCsrf() throws Exception {
		insert(VIEWER, "증거", 0, caseId);

		mockMvc.perform(internal(get(context(caseId)))).andExpect(status().isOk());
		postEvent("{\"event\":\"TOOL_START\",\"payload\":{\"tool\":\"get_chat_context\",\"callId\":\"c1\"}}")
				.andExpect(status().isAccepted());
	}

	@Test
	void internalRequestsDoNotCreateSessions() throws Exception {
		insert(VIEWER, "증거", 0, caseId);

		MvcResult result = mockMvc.perform(internal(get(context(caseId)))).andReturn();

		assertNull(result.getRequest().getSession(false));
		assertNull(result.getResponse().getCookie("JSESSIONID"));
	}

	@Test
	void rejectedTokenValueIsNotLogged(CapturedOutput output) throws Exception {
		mockMvc.perform(get(context(caseId)).header(ServiceTokenFilter.HEADER, "LEAKED-TOKEN-VALUE"));

		assertFalse(output.getAll().contains("LEAKED-TOKEN-VALUE"));
		assertTrue(output.getAll().contains("Rejected service token"));
	}

	// ----- context -----

	@Test
	void legacyChatViewUsesRealMaskedQueryAndRoleUsesChannelInfo() throws Exception {
		insert(VIEWER, "연락처 010-1234-5678", 0, caseId);
		mockMvc.perform(internal(get(context(caseId))).param("view", "chatContext"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.messages[0].text").value("연락처 [PHONE]"));
		mockMvc.perform(internal(get("/internal/cases/{id}/role", caseId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.authorRole").value("VIEWER"))
				.andExpect(jsonPath("$.data.isOfficialOperator").value(false));
	}

	@Test
	void contextHasEvidenceWithSurroundingMessagesInOrder() throws Exception {
		for (int i = 0; i < 12; i++) {
			insert(MODERATOR, "앞 " + i, i, null);
		}
		insert(VIEWER, "증거 1", 20, caseId);
		insert(MODERATOR, "사이", 21, null);
		insert(VIEWER, "증거 2", 22, caseId);
		for (int i = 0; i < 12; i++) {
			insert(MODERATOR, "뒤 " + i, 30 + i, null);
		}

		String body = mockMvc.perform(internal(get(context(caseId) + "?before=2&after=3")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.truncated").value(false))
				.andReturn().getResponse().getContentAsString();

		List<String> texts = JsonPath.read(body, "$.data.messages[*].text");
		assertEquals(List.of("앞 10", "앞 11", "증거 1", "사이", "증거 2", "뒤 0", "뒤 1", "뒤 2"), texts);
		List<String> aliases = JsonPath.read(body, "$.data.messages[*].authorAlias");
		assertEquals(List.of("U2", "U2", "U1", "U2", "U1", "U2", "U2", "U2"), aliases);
	}

	@Test
	void contextDefaultsToTenEachSideAndCapsAtTwentyKeepingEvidence() throws Exception {
		for (int i = 0; i < 12; i++) {
			insert(MODERATOR, "앞 " + i, i, null);
		}
		insert(VIEWER, "증거 1", 20, caseId);
		insert(VIEWER, "증거 2", 21, caseId);
		for (int i = 0; i < 12; i++) {
			insert(MODERATOR, "뒤 " + i, 30 + i, null);
		}

		String body = mockMvc.perform(internal(get(context(caseId))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.messages.length()").value(20))
				.andExpect(jsonPath("$.data.truncated").value(true))
				.andReturn().getResponse().getContentAsString();

		List<String> texts = JsonPath.read(body, "$.data.messages[*].text");
		assertTrue(texts.containsAll(List.of("증거 1", "증거 2")), "사건 메시지는 항상 남는다");
		// 기본 10건씩 22건 중 사건에서 먼 양 끝(앞 2, 뒤 9)이 빠진다
		assertEquals("앞 3", texts.getFirst());
		assertEquals("뒤 8", texts.getLast());
	}

	@Test
	void contextNeverExposesNicknamesOrUserIdsAndMasksPersonalData() throws Exception {
		insert(MODERATOR, "공지", 0, null);
		insert(VIEWER, "연락 010-1234-5678, me@example.com, 123-45-678901, https://evil.test/login?t=SECRET", 1,
				caseId);

		String body = mockMvc.perform(internal(get(context(caseId))))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		for (String leak : List.of("시청자", "운영자", VIEWER.toString(), MODERATOR.toString(), "010-1234-5678",
				"me@example.com", "678901", "SECRET", "/login")) {
			assertFalse(body.contains(leak), leak);
		}
		assertTrue(body.contains("연락 [PHONE], [EMAIL], [ACCOUNT], [URL:evil.test]"));
	}

	@Test
	void contextIncludesHeldMessagesForTheAgent() throws Exception {
		jdbc.update("INSERT INTO platform.messages (channel_id, author_id, client_message_id, text, normalized_text, "
				+ "filter_result, visibility, case_id, sent_at) VALUES (?, ?, ?, '가려진 메시지', '가려진 메시지', "
				+ "'HOLD', 'HELD', ?, ?)", CHANNEL_A, VIEWER, UUID.randomUUID(), caseId, Timestamp.from(T0));

		mockMvc.perform(internal(get(context(caseId))))
				.andExpect(jsonPath("$.data.messages[0].text").value("가려진 메시지"));
	}

	@Test
	void contextValidatesBeforeAndAfter() throws Exception {
		insert(VIEWER, "증거", 0, caseId);
		for (String query : new String[] { "?before=11", "?after=11", "?before=-1", "?after=abc" }) {
			mockMvc.perform(internal(get(context(caseId) + query)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		mockMvc.perform(internal(get(context(caseId) + "?before=0&after=0")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.messages.length()").value(1));
	}

	@Test
	void unknownCaseIs404() throws Exception {
		for (String path : new String[] { context(caseId), "/internal/cases/" + caseId + "/channel-info" }) {
			mockMvc.perform(internal(get(path)))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
		}
		postEvent("{\"event\":\"TOOL_START\",\"payload\":{\"tool\":\"t\",\"callId\":\"c\"}}")
				.andExpect(status().isNotFound());
	}

	// ----- channel-info -----

	@Test
	void channelInfoReportsOfficialDataAuthorRoleAndObservedLinks() throws Exception {
		UUID message = insert(VIEWER, "공식 https://live-a.example.com/e 와 http://evil.test/login?t=x", 0, caseId);

		String body = mockMvc.perform(internal(get("/internal/cases/" + caseId + "/channel-info")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.officialHosts[0]").value("live-a.example.com"))
				.andExpect(jsonPath("$.data.events[0].title").value("신규 가입 이벤트"))
				.andExpect(jsonPath("$.data.authorRole").value("VIEWER"))
				.andExpect(jsonPath("$.data.isOfficialOperator").value(false))
				.andExpect(jsonPath("$.data.observedLinks.length()").value(2))
				.andExpect(jsonPath("$.data.observedLinks[0].messageId").value(message.toString()))
				.andExpect(jsonPath("$.data.observedLinks[0].host").value("live-a.example.com"))
				.andExpect(jsonPath("$.data.observedLinks[0].isOfficial").value(true))
				.andExpect(jsonPath("$.data.observedLinks[1].host").value("evil.test"))
				.andExpect(jsonPath("$.data.observedLinks[1].isOfficial").value(false))
				.andReturn().getResponse().getContentAsString();

		assertFalse(body.contains("/login"), "경로·쿼리는 넣지 않는다");
	}

	@Test
	void moderatorAuthorIsOfficialOperator() throws Exception {
		insert(MODERATOR, "안내", 0, caseId);

		mockMvc.perform(internal(get("/internal/cases/" + caseId + "/channel-info")))
				.andExpect(jsonPath("$.data.authorRole").value("MODERATOR"))
				.andExpect(jsonPath("$.data.isOfficialOperator").value(true));
	}

	// ----- events -----

	@Test
	void agentEventsAreConvertedAndPublishedToTheCaseChannel() throws Exception {
		insert(VIEWER, "증거", 0, caseId);

		postEvent("{\"event\":\"TOOL_START\",\"payload\":{\"tool\":\"get_chat_context\",\"callId\":\"c1\","
				+ "\"args\":{\"text\":\"RAW-ARGUMENT\"}}}").andExpect(status().isAccepted());
		postEvent("{\"event\":\"TOOL_END\",\"payload\":{\"tool\":\"get_chat_context\",\"callId\":\"c1\","
				+ "\"status\":\"OK\",\"elapsedMs\":120,\"result\":\"RAW-RESULT\"}}").andExpect(status().isAccepted());
		postEvent("{\"event\":\"TASK_FAILED\",\"payload\":{\"stage\":\"judge\",\"errorCode\":\"TIMEOUT\"}}")
				.andExpect(status().isAccepted());

		ArgumentCaptor<UUID> channels = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
		verify(guardEvents, times(3)).publish(channels.capture(), names.capture(), data.capture());
		assertEquals(List.of("TOOL_START", "TOOL_END", "TASK_FAILED"), names.getAllValues());
		assertTrue(channels.getAllValues().stream().allMatch(CHANNEL_A::equals));
		assertEquals(new ToolEvent(caseId, "get_chat_context", "c1", null, null), data.getAllValues().get(0));
		assertEquals(new ToolEvent(caseId, "get_chat_context", "c1", "OK", 120), data.getAllValues().get(1));
		assertEquals(new TaskFailedEvent(caseId, "judge", "TIMEOUT", null), data.getAllValues().get(2));
		assertFalse(data.getAllValues().toString().contains("RAW-"), "도구 인자·결과는 넘기지 않는다");
	}

	@Test
	void invalidEventPayloadIs400() throws Exception {
		insert(VIEWER, "증거", 0, caseId);
		for (String json : new String[] {
				"{\"event\":\"TOOL_START\",\"payload\":{\"callId\":\"c1\"}}",
				"{\"event\":\"TOOL_START\",\"payload\":{\"tool\":\" \",\"callId\":\"c1\"}}",
				"{\"event\":\"TOOL_END\",\"payload\":{\"tool\":\"t\",\"callId\":\"c\",\"elapsedMs\":\"fast\"}}",
				"{\"event\":\"TOOL_END\",\"payload\":{\"tool\":\"t\",\"callId\":\"c\",\"elapsedMs\":-1}}",
				"{\"event\":\"TASK_FAILED\",\"payload\":{\"stage\":\"judge\"}}",
				"{\"event\":\"UNKNOWN\",\"payload\":{}}",
				"{\"event\":\"TOOL_START\"}" }) {
			postEvent(json)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		verify(guardEvents, never()).publish(any(), any(), any());
	}

	private ResultActions postEvent(String json) throws Exception {
		return mockMvc.perform(internal(post("/internal/cases/" + caseId + "/events"))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
		return request.header(ServiceTokenFilter.HEADER, TOKEN);
	}

	private static String context(UUID caseId) {
		return "/internal/cases/" + caseId + "/context";
	}

	private UUID insert(UUID author, String text, int secondsAfterT0, UUID caseId) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.messages (id, channel_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, case_id, sent_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, 'PASS', 'PUBLIC', ?, ?)",
				id, CHANNEL_A, author, UUID.randomUUID(), text, text, caseId,
				Timestamp.from(T0.plusSeconds(secondsAfterT0)));
		return id;
	}

}
