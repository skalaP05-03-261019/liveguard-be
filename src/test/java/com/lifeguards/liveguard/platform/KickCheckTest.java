package com.lifeguards.liveguard.platform;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static com.lifeguards.liveguard.support.LoginSupport.withXsrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
import com.lifeguards.liveguard.platform.port.CandidateSink;
import com.lifeguards.liveguard.guard.JdbcCandidateSink;
import com.lifeguards.liveguard.platform.port.InMemoryKickStatus;
import com.lifeguards.liveguard.platform.port.KickStatusQuery;
import com.lifeguards.liveguard.support.MutableClock;
import com.lifeguards.liveguard.support.CaseFixtures;

/** 사건 포트의 영속 구현과 임시 강퇴 확인. 시간은 조절 가능한 Clock으로 시험한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, KickCheckTest.TestClockConfig.class })
class KickCheckTest {

	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID CHANNEL_B = UUID.fromString("20000000-0000-4000-8000-000000000002");
	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID MODERATOR = UUID.fromString("10000000-0000-4000-8000-000000000002");

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(Instant.parse("2026-10-20T12:00:00Z"));
		}

	}

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private Clock clock;
	@Autowired
	private KickStatusQuery kickStatus;
	@Autowired
	private CandidateSink candidateSink;

	private MutableClock mutableClock;
	private InMemoryKickStatus kicks;

	@BeforeEach
	void setUp() {
		mutableClock = assertInstanceOf(MutableClock.class, clock);
		mutableClock.setNow(Instant.parse("2026-10-20T12:00:00Z"));
		kicks = assertInstanceOf(InMemoryKickStatus.class, kickStatus, "B2 구현이 없으면 임시 구현이 쓰인다");
		kicks.clear();
	}

	@AfterEach
	void cleanUp() {
		kicks.clear();
		CaseFixtures.deleteCases(jdbc);
		jdbc.update("DELETE FROM platform.messages");
	}

	@Test
	void candidateSinkIsDurableWhileKickStatusStillUsesTheFallback() {
		assertInstanceOf(JdbcCandidateSink.class, candidateSink);
		assertInstanceOf(InMemoryKickStatus.class, kickStatus);
	}

	@Test
	void kickedMemberGets403KickedWithUntilOnSend() throws Exception {
		Instant until = clock.instant().plus(Duration.ofHours(24));
		kicks.kick(CHANNEL_A, VIEWER, until);

		send(CHANNEL_A, login(mockMvc, "viewer"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("KICKED"))
				.andExpect(jsonPath("$.error.retryable").value(false))
				.andExpect(jsonPath("$.error.until").value("2026-10-21T12:00:00Z"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());

		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform.messages", Integer.class));
	}

	@Test
	void kickedMemberCannotSubscribeToTheChannelStream() throws Exception {
		kicks.kick(CHANNEL_A, VIEWER, clock.instant().plus(Duration.ofHours(1)));

		mockMvc.perform(get("/api/channels/" + CHANNEL_A + "/events").session(login(mockMvc, "viewer"))
				.accept(MediaType.TEXT_EVENT_STREAM))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("KICKED"))
				.andExpect(jsonPath("$.error.until").value("2026-10-20T13:00:00Z"));
	}

	@Test
	void kickAppliesOnlyToThatUserAndChannel() throws Exception {
		kicks.kick(CHANNEL_A, VIEWER, clock.instant().plus(Duration.ofHours(1)));

		send(CHANNEL_A, login(mockMvc, "moderator")).andExpect(status().isCreated());
		send(CHANNEL_B, login(mockMvc, "viewer")).andExpect(status().isCreated());
	}

	@Test
	void kickExpiresWhenTheClockPassesUntil() throws Exception {
		kicks.kick(CHANNEL_A, VIEWER, clock.instant().plus(Duration.ofHours(24)));
		MockHttpSession viewer = login(mockMvc, "viewer");
		send(CHANNEL_A, viewer).andExpect(status().isForbidden());

		mutableClock.advance(Duration.ofHours(24).plusSeconds(1));

		send(CHANNEL_A, viewer).andExpect(status().isCreated());
	}

	@Test
	void replayOfAnAlreadyStoredMessageIsAlsoBlockedWhileKicked() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		UUID clientMessageId = UUID.randomUUID();
		send(CHANNEL_A, clientMessageId, viewer).andExpect(status().isCreated());
		kicks.kick(CHANNEL_A, VIEWER, clock.instant().plus(Duration.ofHours(1)));

		send(CHANNEL_A, clientMessageId, viewer).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("KICKED"));
	}

	@Test
	void nonMemberGets403ForbiddenEvenIfKicked() throws Exception {
		kicks.kick(CHANNEL_A, UUID.fromString("10000000-0000-4000-8000-000000000003"),
				clock.instant().plus(Duration.ofHours(1)));

		send(CHANNEL_A, login(mockMvc, "admin")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void kickedMemberCanStillReadHistory() throws Exception {
		kicks.kick(CHANNEL_A, MODERATOR, clock.instant().plus(Duration.ofHours(1)));

		mockMvc.perform(get("/api/channels/" + CHANNEL_A + "/messages").session(login(mockMvc, "moderator")))
				.andExpect(status().isOk());
	}

	@Test
	void inMemoryKickStatusUsesTheInjectedClock() {
		kicks.kick(CHANNEL_A, VIEWER, clock.instant().plusSeconds(10));
		assertEquals(clock.instant().plusSeconds(10), kicks.activeKickUntil(CHANNEL_A, VIEWER).orElseThrow());

		mutableClock.advance(Duration.ofSeconds(10));

		assertEquals(Optional.empty(), kicks.activeKickUntil(CHANNEL_A, VIEWER));
	}

	private ResultActions send(UUID channelId, MockHttpSession session) throws Exception {
		return send(channelId, UUID.randomUUID(), session);
	}

	private ResultActions send(UUID channelId, UUID clientMessageId, MockHttpSession session) throws Exception {
		return mockMvc.perform(withXsrf(mockMvc, post("/api/channels/" + channelId + "/messages"))
				.session(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"" + clientMessageId + "\",\"text\":\"안녕하세요\"}"));
	}

}
