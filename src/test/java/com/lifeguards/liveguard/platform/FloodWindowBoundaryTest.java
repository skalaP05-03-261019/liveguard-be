package com.lifeguards.liveguard.platform;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static com.lifeguards.liveguard.support.LoginSupport.withXsrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.platform.port.CandidateRequest;
import com.lifeguards.liveguard.platform.port.CandidateSink;
import com.lifeguards.liveguard.platform.port.RecordingCandidateSink;
import com.lifeguards.liveguard.support.MutableClock;

/**
 * FLOOD 10초 창 경계 (BE1 이슈 #15). 조건은 sent_at > (현재 sentAt - 10초): 하한 제외, 마이크로초 정밀도.
 * 조절 가능한 Clock과 직접 넣은 sent_at으로 정확히 10초 전·1마이크로초 안쪽을 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "recording-sink" })
@Import({ TestcontainersConfiguration.class, FloodWindowBoundaryTest.ClockConfig.class })
class FloodWindowBoundaryTest {

	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final Instant T = Instant.parse("2026-10-20T12:00:00Z");
	private static final Duration WINDOW = Duration.ofSeconds(10);
	private static final String TEXT = "같은 말";

	@TestConfiguration
	static class ClockConfig {

		@Bean
		@Primary
		MutableClock boundaryClock() {
			return new MutableClock(T);
		}

	}

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private Clock clock;
	@Autowired
	private CandidateSink sink;

	private MutableClock mutableClock;
	private RecordingCandidateSink recording;
	private MockHttpSession viewer;

	@BeforeEach
	void setUp() throws Exception {
		mutableClock = assertInstanceOf(MutableClock.class, clock);
		mutableClock.setNow(T);
		recording = assertInstanceOf(RecordingCandidateSink.class, sink);
		recording.reset();
		viewer = login(mockMvc, "viewer");
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM platform.messages");
	}

	@Test
	void aMessageExactlyTenSecondsOldIsExcludedFromTheWindow() throws Exception {
		insertAt(T.minus(WINDOW));
		UUID m2 = insertAt(T.minusSeconds(5));
		UUID m3 = insertAt(T.minusSeconds(1));

		send().andExpect(status().isCreated()).andExpect(jsonPath("$.data.visibility").value("HELD"));

		CandidateRequest request = recording.requests.getFirst();
		assertEquals(FilterResult.FLOOD, request.filterResult());
		assertEquals(List.of(m2, m3), request.relatedMessageIds(), "정확히 10초 전 메시지는 들어가지 않는다");
	}

	@Test
	void aMessageOneMicrosecondInsideTheWindowIsIncluded() throws Exception {
		UUID m1 = insertAt(T.minus(WINDOW).plus(1, ChronoUnit.MICROS));
		UUID m2 = insertAt(T.minusSeconds(5));

		send().andExpect(status().isCreated()).andExpect(jsonPath("$.data.visibility").value("HELD"));

		assertEquals(List.of(m1, m2), recording.requests.getFirst().relatedMessageIds());
	}

	@Test
	void anExactBoundaryMessageDoesNotCountTowardTheThreshold() throws Exception {
		insertAt(T.minus(WINDOW));
		insertAt(T.minusSeconds(5));

		send().andExpect(status().isCreated()).andExpect(jsonPath("$.data.visibility").value("PUBLIC"));

		assertEquals(0, recording.requests.size(), "창 안은 현재 포함 2건뿐이라 FLOOD가 아니다");
		assertEquals("PASS", jdbc.queryForObject("SELECT filter_result FROM platform.messages ORDER BY sent_at DESC "
				+ "LIMIT 1", String.class));
	}

	@Test
	void theFirstMessageDropsOutExactlyTenSecondsLaterButTheRestStillFlood() throws Exception {
		Instant t0 = T;
		mutableClock.setNow(t0);
		UUID m1 = sendAndGetId();
		mutableClock.setNow(t0.plusSeconds(5));
		UUID m2 = sendAndGetId();
		mutableClock.setNow(t0.plusSeconds(9));
		UUID m3 = sendAndGetId();
		assertEquals(List.of(m1, m2), recording.requests.get(0).relatedMessageIds());

		mutableClock.setNow(t0.plus(WINDOW));
		UUID m4 = sendAndGetId();

		assertEquals(FilterResult.FLOOD, recording.requests.get(1).filterResult());
		assertEquals(m4, recording.requests.get(1).messageId());
		assertEquals(List.of(m2, m3), recording.requests.get(1).relatedMessageIds(),
				"첫 메시지는 정확히 10초 전이라 빠진다");
	}

	@Test
	void sentAtIsTruncatedToMicrosecondsAndPassedToTheSink() throws Exception {
		insertAt(T.minusSeconds(5));
		insertAt(T.minusSeconds(1));
		mutableClock.setNow(T.plusNanos(123_456_789));

		send().andExpect(status().isCreated());

		Instant expected = T.plusNanos(123_456_000);
		assertEquals(expected, recording.requests.getFirst().sentAt());
		assertEquals(expected, jdbc.queryForObject("SELECT sent_at FROM platform.messages ORDER BY sent_at DESC "
				+ "LIMIT 1", OffsetDateTime.class).toInstant());
	}

	private UUID insertAt(Instant sentAt) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.messages (id, channel_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, sent_at) VALUES (?, ?, ?, ?, ?, ?, 'PASS', 'PUBLIC', ?)",
				id, CHANNEL_A, VIEWER, UUID.randomUUID(), TEXT, TEXT, Timestamp.from(sentAt));
		return id;
	}

	private UUID sendAndGetId() throws Exception {
		UUID clientMessageId = UUID.randomUUID();
		send(clientMessageId).andExpect(status().isCreated());
		return jdbc.queryForObject("SELECT id FROM platform.messages WHERE client_message_id = ?", UUID.class,
				clientMessageId);
	}

	private ResultActions send() throws Exception {
		return send(UUID.randomUUID());
	}

	private ResultActions send(UUID clientMessageId) throws Exception {
		return mockMvc.perform(withXsrf(mockMvc, post("/api/channels/" + CHANNEL_A + "/messages"))
				.session(viewer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"" + clientMessageId + "\",\"text\":\"" + TEXT + "\"}"));
	}

}
