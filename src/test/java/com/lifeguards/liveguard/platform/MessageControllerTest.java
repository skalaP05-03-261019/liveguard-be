package com.lifeguards.liveguard.platform;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static com.lifeguards.liveguard.support.LoginSupport.withXsrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.common.security.AuthenticatedUser;
import com.lifeguards.liveguard.contracts.dto.request.SendMessageRequest;
import com.lifeguards.liveguard.contracts.enums.Role;
import com.lifeguards.liveguard.contracts.enums.Visibility;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, MessageControllerTest.EventRecorder.class })
class MessageControllerTest {

	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private EventRecorder recorder;
	@Autowired
	private MessageService messageService;
	@Autowired
	private TransactionTemplate transactionTemplate;

	private UUID unjoinedChannel;

	@BeforeEach
	void setUp() {
		recorder.events.clear();
		unjoinedChannel = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.channels (id, name) VALUES (?, 'unjoined')", unjoinedChannel);
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM platform.messages");
		jdbc.update("DELETE FROM platform.channels WHERE id = ?", unjoinedChannel);
	}

	@Test
	void savesPassPublicMessageWithNormalizedText() throws Exception {
		UUID clientMessageId = UUID.randomUUID();

		mockMvc.perform(send(CHANNEL_A, clientMessageId, "ＨＥＬＬＯ   wwwwww!!!", login(mockMvc, "viewer")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.channelId").value(CHANNEL_A.toString()))
				.andExpect(jsonPath("$.data.authorAlias").value("시청자"))
				.andExpect(jsonPath("$.data.text").value("ＨＥＬＬＯ   wwwwww!!!"))
				.andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
				.andExpect(jsonPath("$.data.version").value(1))
				.andExpect(jsonPath("$.data.isMine").value(true))
				.andExpect(jsonPath("$.data.sentAt").isNotEmpty())
				.andExpect(jsonPath("$.traceId").isNotEmpty());

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT text, normalized_text, filter_result, visibility, version, author_id FROM platform.messages "
						+ "WHERE client_message_id = ?", clientMessageId);
		assertEquals("ＨＥＬＬＯ   wwwwww!!!", row.get("text"));
		assertEquals("hello ww!!", row.get("normalized_text"));
		assertEquals("PASS", row.get("filter_result"));
		assertEquals("PUBLIC", row.get("visibility"));
		assertEquals(1, row.get("version"));
		assertEquals(VIEWER, row.get("author_id"));
	}

	@Test
	void sameIdAndSameTextReturnsTheFirstResponseWithoutSavingOrPublishingAgain() throws Exception {
		UUID clientMessageId = UUID.randomUUID();
		MockHttpSession session = login(mockMvc, "viewer");

		String first = mockMvc.perform(send(CHANNEL_A, clientMessageId, "hello", session))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String second = mockMvc.perform(send(CHANNEL_A, clientMessageId, "hello", session))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

		assertEquals(JsonPath.<Object>read(first, "$.data"), JsonPath.<Object>read(second, "$.data"));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM platform.messages", Integer.class));
		assertEquals(1, recorder.events.size());
	}

	@Test
	void sameIdWithDifferentTextIs409AndKeepsTheOriginal() throws Exception {
		UUID clientMessageId = UUID.randomUUID();
		MockHttpSession session = login(mockMvc, "viewer");
		mockMvc.perform(send(CHANNEL_A, clientMessageId, "first text", session)).andExpect(status().isCreated());
		recorder.events.clear();

		MvcResult result = mockMvc.perform(send(CHANNEL_A, clientMessageId, "SECOND-TEXT", session))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"))
				.andExpect(jsonPath("$.error.retryable").value(false))
				.andReturn();

		assertFalse(result.getResponse().getContentAsString().contains("SECOND-TEXT"));
		assertFalse(result.getResponse().getContentAsString().contains("first text"));
		assertEquals("first text", jdbc.queryForObject("SELECT text FROM platform.messages", String.class));
		assertEquals(0, recorder.events.size());
	}

	@Test
	void sameClientMessageIdFromDifferentUsersAreIndependent() throws Exception {
		UUID clientMessageId = UUID.randomUUID();

		mockMvc.perform(send(CHANNEL_A, clientMessageId, "from viewer", login(mockMvc, "viewer")))
				.andExpect(status().isCreated());
		mockMvc.perform(send(CHANNEL_A, clientMessageId, "from moderator", login(mockMvc, "moderator")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.authorAlias").value("운영자"));

		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM platform.messages", Integer.class));
	}

	@Test
	void nonMembersGet403ForNotJoinedUnknownChannelAndPlatformAdmin() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		for (UUID channel : new UUID[] { unjoinedChannel, UUID.randomUUID() }) {
			mockMvc.perform(send(channel, UUID.randomUUID(), "hello", viewer))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		}
		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), "hello", login(mockMvc, "admin")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform.messages", Integer.class));
		assertEquals(0, recorder.events.size());
	}

	@Test
	void unauthenticatedIs401AndMissingCsrfIs403() throws Exception {
		mockMvc.perform(withXsrf(mockMvc, post("/api/channels/" + CHANNEL_A + "/messages"))
				.contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), "hello")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

		mockMvc.perform(post("/api/channels/" + CHANNEL_A + "/messages").session(login(mockMvc, "viewer"))
				.contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), "hello")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void invalidRequestsAre400() throws Exception {
		MockHttpSession session = login(mockMvc, "viewer");
		String id = "\"clientMessageId\":\"" + UUID.randomUUID() + "\"";

		for (String json : new String[] {
				"{" + id + ",\"text\":\"   \"}",
				"{" + id + ",\"text\":\"" + "x".repeat(501) + "\"}",
				"{" + id + ",\"text\":\"hello\",\"role\":\"MODERATOR\"}",
				"{\"text\":\"hello\"}",
				"{" + id + ",\"text\":\"a\\u0000b\"}",
				"{" + id + ",\"text\":\"a\\ud800b\"}" }) {
			mockMvc.perform(withXsrf(mockMvc, post("/api/channels/" + CHANNEL_A + "/messages")).session(session)
					.contentType(MediaType.APPLICATION_JSON).content(json))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		mockMvc.perform(withXsrf(mockMvc, post("/api/channels/not-a-uuid/messages")).session(session)
				.contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), "hello")))
				.andExpect(status().isBadRequest());
		assertEquals(0, recorder.events.size());
	}

	@ParameterizedTest
	@ValueSource(strings = { "😀", "한글 ㅋㅋㅋㅋ" })
	void acceptsSupplementaryCharactersAndKorean(String text) throws Exception {
		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), text, login(mockMvc, "viewer")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.text").value(text));
	}

	@Test
	void publishesChatEventOnlyAfterCommit() throws Exception {
		mockMvc.perform(send(CHANNEL_A, UUID.randomUUID(), "hello", login(mockMvc, "moderator")))
				.andExpect(status().isCreated());

		assertEquals(1, recorder.events.size());
		MessageCreatedEvent event = recorder.events.get(0);
		assertEquals(CHANNEL_A, event.channelId());
		assertEquals(UUID.fromString("10000000-0000-4000-8000-000000000002"), event.authorId());
		assertEquals("운영자", event.chat().authorAlias());
		assertEquals("hello", event.chat().text());
		assertEquals(Visibility.PUBLIC, event.chat().visibility());
		assertEquals(1, event.chat().version());
	}

	@Test
	void doesNotPublishWhenTheTransactionRollsBack() {
		AuthenticatedUser viewer = new AuthenticatedUser() {
			@Override
			public UUID userId() {
				return VIEWER;
			}

			@Override
			public String nickname() {
				return "시청자";
			}

			@Override
			public Role role() {
				return Role.VIEWER;
			}
		};

		transactionTemplate.executeWithoutResult(tx -> {
			messageService.send(CHANNEL_A, viewer, new SendMessageRequest(UUID.randomUUID(), "rolled back"));
			assertEquals(0, recorder.events.size(), "커밋 전에는 발행되지 않는다");
			tx.setRollbackOnly();
		});

		assertEquals(0, recorder.events.size());
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform.messages", Integer.class));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder send(UUID channelId,
			UUID clientMessageId, String text, MockHttpSession session) throws Exception {
		return withXsrf(mockMvc, post("/api/channels/" + channelId + "/messages"))
				.session(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(clientMessageId, text));
	}

	private static String body(UUID clientMessageId, String text) {
		return "{\"clientMessageId\":\"" + clientMessageId + "\",\"text\":\"" + text + "\"}";
	}

	@Component
	static class EventRecorder {

		final List<MessageCreatedEvent> events = new CopyOnWriteArrayList<>();

		@TransactionalEventListener
		void on(MessageCreatedEvent event) {
			events.add(event);
		}

	}

}
