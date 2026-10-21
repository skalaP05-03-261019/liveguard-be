package com.lifeguards.liveguard.platform;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static com.lifeguards.liveguard.support.LoginSupport.withXsrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

import com.jayway.jsonpath.JsonPath;
import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.platform.port.CandidateRequest;
import com.lifeguards.liveguard.platform.port.CandidateSink;
import com.lifeguards.liveguard.platform.port.RecordingCandidateSink;
import com.lifeguards.liveguard.support.CaseFixtures;
import com.lifeguards.liveguard.support.MutableClock;

/** 방송 시작 → 채팅 → 후보까지 같은 liveId, 도배는 같은 사용자·방송 범위(이전 방송 제외). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "recording-sink" })
@Import({ TestcontainersConfiguration.class, LiveCandidateFloodTest.ClockConfig.class })
class LiveCandidateFloodTest {

	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID MODERATOR = UUID.fromString("10000000-0000-4000-8000-000000000002");
	private static final Instant T = Instant.parse("2026-10-21T12:00:00Z");
	private static final String TEXT = "같은 말";

	@TestConfiguration
	static class ClockConfig {

		@Bean
		@Primary
		MutableClock liveClock() {
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
	private UUID channel;
	private MockHttpSession viewer;
	private MockHttpSession moderator;

	@BeforeEach
	void setUp() throws Exception {
		mutableClock = assertInstanceOf(MutableClock.class, clock);
		mutableClock.setNow(T);
		recording = assertInstanceOf(RecordingCandidateSink.class, sink);
		recording.reset();
		channel = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.channels (id, name) VALUES (?, 'live-flood')", channel);
		jdbc.update("INSERT INTO platform.channel_members (channel_id, user_id, member_role) VALUES "
				+ "(?, ?, 'MODERATOR'), (?, ?, 'VIEWER')", channel, MODERATOR, channel, VIEWER);
		viewer = login(mockMvc, "viewer");
		moderator = login(mockMvc, "moderator");
	}

	@AfterEach
	void cleanUp() {
		CaseFixtures.deleteCases(jdbc);
		jdbc.update("DELETE FROM platform.messages WHERE channel_id = ?", channel);
		jdbc.update("DELETE FROM platform.lives WHERE channel_id = ?", channel);
		jdbc.update("DELETE FROM platform.channel_members WHERE channel_id = ?", channel);
		jdbc.update("DELETE FROM platform.channels WHERE id = ?", channel);
	}

	@Test
	void theStartedLiveIdReachesTheMessageAndTheCandidate() throws Exception {
		UUID liveId = startLive();

		UUID messageId = send(liveId, "씨발");

		assertEquals(liveId, jdbc.queryForObject("SELECT live_id FROM platform.messages WHERE id = ?", UUID.class,
				messageId));
		assertEquals(1, recording.requests.size());
		CandidateRequest request = recording.requests.getFirst();
		assertEquals(liveId, request.liveId());
		assertEquals(channel, request.channelId());
		assertEquals(messageId, request.messageId());
	}

	@Test
	void floodCountsOnlyTheCurrentLive() throws Exception {
		UUID first = startLive();
		send(first, TEXT);
		tick();
		send(first, TEXT);
		tick();
		endLive(first);
		tick();
		UUID second = startLive();

		// 이전 방송의 두 번은 세지 않는다: 10초 안의 세 번째지만 새 방송의 첫 번째라 PASS
		UUID secondFirst = send(second, TEXT);
		assertEquals("PASS", filterResult(secondFirst));
		assertEquals(0, recording.requests.size());

		tick();
		UUID secondSecond = send(second, TEXT);
		tick();
		UUID secondThird = send(second, TEXT);
		assertEquals("FLOOD", filterResult(secondThird));
		assertEquals(1, recording.requests.size());
		CandidateRequest flood = recording.requests.getFirst();
		assertEquals(FilterResult.FLOOD, flood.filterResult());
		assertEquals(second, flood.liveId());
		assertEquals(List.of(secondFirst, secondSecond), flood.relatedMessageIds());
	}

	@Test
	void messagesFromBeforeLivesExistedAreNotCountedForFlood() throws Exception {
		// 방송 도입 전 메시지(live_id NULL, A-17) 두 개
		for (int i = 0; i < 2; i++) {
			jdbc.update("INSERT INTO platform.messages (channel_id, author_id, client_message_id, text, normalized_text, "
					+ "filter_result, visibility, sent_at) VALUES (?, ?, ?, ?, ?, 'PASS', 'PUBLIC', ?)",
					channel, VIEWER, UUID.randomUUID(), TEXT, TEXT, java.sql.Timestamp.from(T.minusSeconds(2 - i)));
		}
		UUID liveId = startLive();

		assertEquals("PASS", filterResult(send(liveId, TEXT)));
		assertEquals(0, recording.requests.size());
	}

	private void tick() {
		mutableClock.advance(Duration.ofSeconds(1));
	}

	private UUID startLive() throws Exception {
		String body = mockMvc.perform(withXsrf(mockMvc, post("/api/channels/" + channel + "/lives")).session(moderator))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.data.liveId"));
	}

	private void endLive(UUID liveId) throws Exception {
		mockMvc.perform(withXsrf(mockMvc, post("/api/lives/" + liveId + "/end")).session(moderator))
				.andExpect(status().isOk());
	}

	private UUID send(UUID liveId, String text) throws Exception {
		String body = mockMvc.perform(withXsrf(mockMvc, post("/api/lives/" + liveId + "/messages")).session(viewer)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"text\":\"" + text + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.data.messageId"));
	}

	private String filterResult(UUID messageId) {
		return jdbc.queryForObject("SELECT filter_result FROM platform.messages WHERE id = ?", String.class, messageId);
	}

}
