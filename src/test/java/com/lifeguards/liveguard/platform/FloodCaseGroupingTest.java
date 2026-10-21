package com.lifeguards.liveguard.platform;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static com.lifeguards.liveguard.support.LoginSupport.withXsrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.filter.MessageFilter;
import com.lifeguards.liveguard.platform.port.CandidateRequest;
import com.lifeguards.liveguard.platform.port.CandidateSink;
import com.lifeguards.liveguard.platform.port.RecordingCandidateSink;

/**
 * FLOOD 3→4→5번째 사건 묶기 (BE1 이슈 #15). 테스트용 sink가 B2의 재사용 규칙을 흉내 낸다:
 * relatedMessageIds 중 판정 전(ANALYZING) 사건에 연결된 메시지가 있으면 그 caseId를 재사용(가장 최근 메시지 우선),
 * 없으면 새 사건.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "recording-sink" })
@Import(TestcontainersConfiguration.class)
class FloodCaseGroupingTest {

	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final String TEXT = "같은 말";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private CandidateSink sink;

	private RecordingCandidateSink recording;
	private final Set<UUID> analyzing = ConcurrentHashMap.newKeySet();
	private MockHttpSession viewer;

	@BeforeEach
	void setUp() throws Exception {
		recording = assertInstanceOf(RecordingCandidateSink.class, sink);
		recording.reset();
		analyzing.clear();
		recording.behavior = request -> {
			for (int i = request.relatedMessageIds().size() - 1; i >= 0; i--) {
				List<UUID> linked = jdbc.queryForList("SELECT case_id FROM platform.messages WHERE id = ? "
						+ "AND case_id IS NOT NULL", UUID.class, request.relatedMessageIds().get(i));
				if (!linked.isEmpty() && analyzing.contains(linked.getFirst())) {
					return linked.getFirst();
				}
			}
			UUID created = UUID.randomUUID();
			analyzing.add(created);
			return created;
		};
		viewer = login(mockMvc, "viewer");
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM platform.messages");
	}

	@Test
	void thirdFourthAndFifthMessagesJoinTheSameCaseWithGrowingRelatedMessages() throws Exception {
		UUID m1 = send(UUID.randomUUID());
		UUID m2 = send(UUID.randomUUID());
		assertEquals(0, recording.requests.size(), "앞의 두 메시지는 PASS라 사건을 열지 않는다");

		UUID m3 = send(UUID.randomUUID());
		assertEquals(1, recording.requests.size());
		assertRelated(0, FilterResult.FLOOD, List.of(m1, m2), m3);
		UUID caseX = caseIdOf(m3);
		assertEquals(List.of(caseX, caseX), List.of(caseIdOf(m1), caseIdOf(m2)), "증거 메시지도 같은 사건에 연결");

		UUID m4 = send(UUID.randomUUID());
		assertRelated(1, FilterResult.FLOOD, List.of(m1, m2, m3), m4);
		assertEquals(caseX, caseIdOf(m4), "ANALYZING 사건에 연결된 메시지가 있으면 같은 사건을 재사용");

		UUID m5 = send(UUID.randomUUID());
		assertRelated(2, FilterResult.FLOOD, List.of(m1, m2, m3, m4), m5);
		assertEquals(caseX, caseIdOf(m5));

		assertEquals(1, analyzing.size(), "3·4·5번째는 한 사건: 누적 1회");
		assertEquals(List.of("PUBLIC", "PUBLIC", "HELD", "HELD", "HELD"), jdbc.queryForList(
				"SELECT visibility FROM platform.messages ORDER BY sent_at, id", String.class));
		for (CandidateRequest request : recording.requests) {
			assertEquals(List.of(MessageFilter.REPEATED_TEXT), request.matchingTags());
			assertEquals(MessageFilter.version(), request.filterVersion());
		}
	}

	@Test
	void replayingAFloodMessageDoesNotCallTheSinkAgainOrChangeLaterRelatedMessages() throws Exception {
		UUID m1 = send(UUID.randomUUID());
		UUID m2 = send(UUID.randomUUID());
		UUID thirdClientId = UUID.randomUUID();
		UUID m3 = send(thirdClientId);
		assertEquals(1, recording.requests.size());

		sendRaw(thirdClientId).andExpect(status().isCreated());
		sendRaw(thirdClientId).andExpect(status().isCreated());

		assertEquals(1, recording.requests.size(), "재전송은 CandidateSink를 부르지 않는다");
		assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM platform.messages", Integer.class));

		UUID m4 = send(UUID.randomUUID());
		assertRelated(1, FilterResult.FLOOD, List.of(m1, m2, m3), m4);
	}

	@Test
	void anAlreadyJudgedCaseIsNotReusedAndTheNewestLinkedCaseWins() throws Exception {
		UUID m1 = send(UUID.randomUUID());
		UUID m2 = send(UUID.randomUUID());
		UUID m3 = send(UUID.randomUUID());
		UUID caseX = caseIdOf(m3);

		analyzing.remove(caseX);
		UUID m4 = send(UUID.randomUUID());

		assertRelated(1, FilterResult.FLOOD, List.of(m1, m2, m3), m4);
		UUID caseY = caseIdOf(m4);
		assertEquals(false, caseY.equals(caseX), "판정된 사건은 재사용하지 않고 새 사건을 연다");
		assertEquals(List.of(caseX, caseX, caseX), List.of(caseIdOf(m1), caseIdOf(m2), caseIdOf(m3)),
				"이미 연결된 메시지의 case_id는 바뀌지 않는다");

		UUID m5 = send(UUID.randomUUID());
		assertRelated(2, FilterResult.FLOOD, List.of(m1, m2, m3, m4), m5);
		assertEquals(caseY, caseIdOf(m5), "판정 전 사건 Y에 연결된 가장 최근 메시지를 따른다");
	}

	private void assertRelated(int requestIndex, FilterResult expectedResult, List<UUID> expectedRelated,
			UUID expectedMessage) {
		CandidateRequest request = recording.requests.get(requestIndex);
		assertEquals(expectedResult, request.filterResult());
		assertEquals(expectedMessage, request.messageId());
		assertEquals(expectedRelated, request.relatedMessageIds());
	}

	private UUID caseIdOf(UUID messageId) {
		return jdbc.queryForObject("SELECT case_id FROM platform.messages WHERE id = ?", UUID.class, messageId);
	}

	/** 메시지를 보내고 저장된 id를 돌려준다. */
	private UUID send(UUID clientMessageId) throws Exception {
		sendRaw(clientMessageId).andExpect(status().isCreated());
		return jdbc.queryForObject("SELECT id FROM platform.messages WHERE client_message_id = ?", UUID.class,
				clientMessageId);
	}

	private ResultActions sendRaw(UUID clientMessageId) throws Exception {
		return mockMvc.perform(withXsrf(mockMvc, post("/api/channels/" + CHANNEL_A + "/messages"))
				.session(viewer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"" + clientMessageId + "\",\"text\":\"" + TEXT + "\"}"));
	}

}
