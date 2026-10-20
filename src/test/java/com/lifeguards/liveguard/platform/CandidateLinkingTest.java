package com.lifeguards.liveguard.platform;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static com.lifeguards.liveguard.support.LoginSupport.withXsrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.filter.MessageFilter;
import com.lifeguards.liveguard.filter.TextNormalizer;
import com.lifeguards.liveguard.platform.port.CandidateRequest;
import com.lifeguards.liveguard.platform.port.CandidateSink;
import com.lifeguards.liveguard.platform.port.RecordingCandidateSink;

/** 스캔된 CandidateSink 구현(B2 대역)이 임시 구현을 대체하고, 사건 연결이 메시지 저장과 한 트랜잭션인지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "recording-sink" })
@Import({ TestcontainersConfiguration.class, MessageControllerTest.EventRecorder.class })
class CandidateLinkingTest {

	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final String HOLD_TEXT = "공식 이벤트 http://evil.test 에서 로그인하세요";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private CandidateSink sink;
	@Autowired
	private MessageControllerTest.EventRecorder events;

	private RecordingCandidateSink recording;

	@BeforeEach
	void setUp() {
		recording = assertInstanceOf(RecordingCandidateSink.class, sink, "스캔된 구현이 임시 구현을 대체해야 한다");
		recording.reset();
		events.events.clear();
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM platform.messages");
	}

	@Test
	void passMessageDoesNotOpenACase() throws Exception {
		send("평범한 채팅", login(mockMvc, "viewer")).andExpect(status().isCreated());

		assertEquals(0, recording.requests.size());
		assertNull(caseIdOf("평범한 채팅"));
	}

	@Test
	void reviewAndHoldOpenACaseAndStoreItsId() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		UUID reviewCase = UUID.randomUUID();
		UUID holdCase = UUID.randomUUID();
		recording.behavior = request -> request.filterResult() == FilterResult.HOLD ? holdCase : reviewCase;

		send("무대 미쳤다ㅋㅋ", viewer).andExpect(status().isCreated());
		send(HOLD_TEXT, viewer).andExpect(status().isCreated());

		assertEquals(2, recording.requests.size());
		CandidateRequest review = recording.requests.get(0);
		assertEquals(CHANNEL_A, review.channelId());
		assertEquals(VIEWER, review.userId());
		assertEquals(FilterResult.REVIEW, review.filterResult());
		assertEquals(List.of(MessageFilter.ATTACK_TERM), review.matchingTags());
		assertEquals(List.of(), review.relatedMessageIds());
		assertEquals(reviewCase, caseIdOf("무대 미쳤다ㅋㅋ"));
		assertEquals(review.messageId(), idOf("무대 미쳤다ㅋㅋ"));
		assertEquals(sentAtOf("무대 미쳤다ㅋㅋ"), review.sentAt());

		CandidateRequest hold = recording.requests.get(1);
		assertEquals(FilterResult.HOLD, hold.filterResult());
		assertEquals(List.of(MessageFilter.OFFICIAL_CLAIM, MessageFilter.CREDENTIAL_REQUEST,
				MessageFilter.UNOFFICIAL_URL), hold.matchingTags());
		assertEquals(holdCase, caseIdOf(HOLD_TEXT));
	}

	@Test
	void caseIsOpenedInTheSameTransactionAsTheMessage() throws Exception {
		AtomicReference<Integer> visibleRows = new AtomicReference<>();
		recording.behavior = request -> {
			visibleRows.set(jdbc.queryForObject("SELECT count(*) FROM platform.messages WHERE id = ?",
					Integer.class, request.messageId()));
			return UUID.randomUUID();
		};

		send("무대 미쳤다ㅋㅋ", login(mockMvc, "viewer")).andExpect(status().isCreated());

		assertEquals(1, visibleRows.get(), "커밋 전이어도 같은 트랜잭션이라 방금 저장한 메시지가 보인다");
	}

	@Test
	void replayDoesNotOpenAnotherCase() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		UUID clientMessageId = UUID.randomUUID();
		send(clientMessageId, "무대 미쳤다ㅋㅋ", viewer).andExpect(status().isCreated());
		send(clientMessageId, "무대 미쳤다ㅋㅋ", viewer).andExpect(status().isCreated());

		assertEquals(1, recording.requests.size());
	}

	@Test
	void sinkFailureRollsBackTheMessageAndAnswers503() throws Exception {
		recording.behavior = request -> {
			throw new IllegalStateException("SECRET-CHAT-TEXT boom");
		};
		MockHttpSession viewer = login(mockMvc, "viewer");
		UUID clientMessageId = UUID.randomUUID();

		String body = send(clientMessageId, "무대 미쳤다ㅋㅋ", viewer)
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("TEMPORARY_UNAVAILABLE"))
				.andExpect(jsonPath("$.error.retryable").value(true))
				.andReturn().getResponse().getContentAsString();

		assertTrue(!body.contains("SECRET-CHAT-TEXT"));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform.messages", Integer.class));
		assertEquals(0, events.events.size());

		// 같은 clientMessageId로 재시도하면 정상 처리된다
		recording.reset();
		send(clientMessageId, "무대 미쳤다ㅋㅋ", viewer).andExpect(status().isCreated());
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM platform.messages", Integer.class));
	}

	@Test
	void floodThirdMessageLinksEarlierMessagesToTheSameCaseWithoutChangingVisibility() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		UUID floodCase = UUID.randomUUID();
		recording.behavior = request -> floodCase;

		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			send("도배 문장", viewer).andExpect(status().isCreated());
			ids.add(idOfLatest("도배 문장"));
		}

		assertEquals(1, recording.requests.size(), "첫 두 메시지는 PASS라 세 번째만 사건을 연다");
		CandidateRequest request = recording.requests.get(0);
		assertEquals(FilterResult.FLOOD, request.filterResult());
		assertEquals(List.of(MessageFilter.REPEATED_TEXT), request.matchingTags());
		assertEquals(ids.get(2), request.messageId());
		assertEquals(ids.subList(0, 2), request.relatedMessageIds());
		for (UUID id : ids) {
			assertEquals(floodCase, jdbc.queryForObject("SELECT case_id FROM platform.messages WHERE id = ?",
					UUID.class, id));
		}
		assertEquals(List.of("PUBLIC", "PUBLIC", "HELD"), jdbc.queryForList(
				"SELECT visibility FROM platform.messages ORDER BY sent_at, id", String.class));
	}

	@Test
	void alreadyLinkedMessagesKeepTheirCase() throws Exception {
		UUID otherCase = UUID.randomUUID();
		UUID newCase = UUID.randomUUID();
		UUID linked = insert("도배 문장", otherCase, Instant.now().minusSeconds(3));
		UUID unlinked = insert("도배 문장", null, Instant.now().minusSeconds(2));
		recording.behavior = request -> newCase;

		send("도배 문장", login(mockMvc, "viewer")).andExpect(status().isCreated());

		assertEquals(List.of(linked, unlinked), recording.requests.get(0).relatedMessageIds());
		assertEquals(otherCase, jdbc.queryForObject("SELECT case_id FROM platform.messages WHERE id = ?",
				UUID.class, linked), "이미 다른 사건에 연결된 메시지는 바뀌지 않는다");
		assertEquals(newCase, jdbc.queryForObject("SELECT case_id FROM platform.messages WHERE id = ?",
				UUID.class, unlinked));
	}

	@Test
	void laterFloodMessagesKeepEarlierLinksAndTheSinkDecidesGrouping() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		UUID firstCase = UUID.randomUUID();
		UUID secondCase = UUID.randomUUID();
		List<UUID> answers = new ArrayList<>(List.of(firstCase, secondCase));
		recording.behavior = request -> answers.remove(0);

		for (int i = 0; i < 4; i++) {
			send("도배 문장", viewer).andExpect(status().isCreated());
		}

		assertEquals(2, recording.requests.size());
		assertEquals(3, recording.requests.get(1).relatedMessageIds().size(), "창 안의 이전 메시지 전부");
		List<UUID> cases = jdbc.queryForList("SELECT case_id FROM platform.messages ORDER BY sent_at, id",
				UUID.class);
		assertEquals(List.of(firstCase, firstCase, firstCase, secondCase), cases);
	}

	@Test
	void certainProfanityOpensAHoldCaseWithTheProfanityTagAndNoRelatedMessages() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		UUID holdCase = UUID.randomUUID();
		recording.behavior = request -> holdCase;

		for (int i = 0; i < 3; i++) {
			send("씨발", viewer).andExpect(status().isCreated());
		}

		// 같은 욕설 3회는 FLOOD가 아니라 HOLD 3건: 사건을 묶을지는 B2 open()이 정한다
		assertEquals(3, recording.requests.size());
		for (CandidateRequest request : recording.requests) {
			assertEquals(FilterResult.HOLD, request.filterResult());
			assertEquals(List.of(), request.relatedMessageIds());
		}
		assertEquals(List.of(MessageFilter.CERTAIN_PROFANITY), recording.requests.get(0).matchingTags());
		assertEquals(List.of(MessageFilter.CERTAIN_PROFANITY, MessageFilter.REPEATED_TEXT),
				recording.requests.get(2).matchingTags());
		assertEquals(List.of(holdCase, holdCase, holdCase), jdbc.queryForList(
				"SELECT case_id FROM platform.messages ORDER BY sent_at, id", UUID.class));
	}

	@Test
	void relatedMessagesAreEmptyForHoldEvenWhenRepeated() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		for (int i = 0; i < 3; i++) {
			send(HOLD_TEXT, viewer).andExpect(status().isCreated());
		}

		assertEquals(3, recording.requests.size());
		assertTrue(recording.requests.stream().allMatch(request -> request.filterResult() == FilterResult.HOLD));
		assertTrue(recording.requests.stream().allMatch(request -> request.relatedMessageIds().isEmpty()));
	}

	private ResultActions send(String text, MockHttpSession session)
			throws Exception {
		return send(UUID.randomUUID(), text, session);
	}

	private ResultActions send(UUID clientMessageId, String text,
			MockHttpSession session) throws Exception {
		MockHttpServletRequestBuilder request = withXsrf(mockMvc, post("/api/channels/" + CHANNEL_A + "/messages"))
				.session(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"" + clientMessageId + "\",\"text\":\"" + text + "\"}");
		return mockMvc.perform(request);
	}

	private UUID insert(String text, UUID caseId, Instant sentAt) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.messages (id, channel_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, case_id, sent_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, 'PASS', 'PUBLIC', ?, ?)",
				id, CHANNEL_A, VIEWER, UUID.randomUUID(), text, TextNormalizer.normalize(text), caseId,
				Timestamp.from(sentAt));
		return id;
	}

	private UUID caseIdOf(String text) {
		return jdbc.queryForObject("SELECT case_id FROM platform.messages WHERE text = ?", UUID.class, text);
	}

	private UUID idOf(String text) {
		return jdbc.queryForObject("SELECT id FROM platform.messages WHERE text = ?", UUID.class, text);
	}

	private UUID idOfLatest(String text) {
		return jdbc.queryForObject("SELECT id FROM platform.messages WHERE text = ? ORDER BY sent_at DESC, id DESC "
				+ "LIMIT 1", UUID.class, text);
	}

	private Instant sentAtOf(String text) {
		return jdbc.queryForObject("SELECT sent_at FROM platform.messages WHERE text = ?",
				OffsetDateTime.class, text).toInstant();
	}

}
