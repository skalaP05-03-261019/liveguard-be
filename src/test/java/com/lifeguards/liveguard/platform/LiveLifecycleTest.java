package com.lifeguards.liveguard.platform;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static com.lifeguards.liveguard.support.LoginSupport.withXsrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.event.TransactionalEventListener;

import com.jayway.jsonpath.JsonPath;
import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.platform.port.LiveEndedEvent;
import com.lifeguards.liveguard.platform.port.LiveQuery;

/** 방송 시작·종료·현재 조회(A-1, A-2, A-19). 시드 채널 A·B의 방송은 건드리지 않도록 전용 채널을 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, LiveLifecycleTest.EndedRecorder.class })
class LiveLifecycleTest {

	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID MODERATOR = UUID.fromString("10000000-0000-4000-8000-000000000002");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private EndedRecorder recorder;
	@Autowired
	private LiveQuery liveQuery;

	/** moderator=MODERATOR, viewer=VIEWER */
	private UUID channel;
	/** viewer=STREAMER */
	private UUID streamerChannel;

	@BeforeEach
	void setUp() {
		recorder.events.clear();
		channel = UUID.randomUUID();
		streamerChannel = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.channels (id, name) VALUES (?, 'live-test'), (?, 'streamer-test')",
				channel, streamerChannel);
		jdbc.update("INSERT INTO platform.channel_members (channel_id, user_id, member_role) VALUES "
				+ "(?, ?, 'MODERATOR'), (?, ?, 'VIEWER'), (?, ?, 'STREAMER')",
				channel, MODERATOR, channel, VIEWER, streamerChannel, VIEWER);
	}

	@AfterEach
	void cleanUp() {
		for (UUID id : List.of(channel, streamerChannel)) {
			jdbc.update("DELETE FROM platform.messages WHERE channel_id = ?", id);
			jdbc.update("DELETE FROM platform.lives WHERE channel_id = ?", id);
			jdbc.update("DELETE FROM platform.channel_members WHERE channel_id = ?", id);
			jdbc.update("DELETE FROM platform.channels WHERE id = ?", id);
		}
	}

	@Test
	void moderatorStartsALiveAndMembersSeeItAsCurrent() throws Exception {
		String body = mockMvc.perform(start(channel, login(mockMvc, "moderator")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.channelId").value(channel.toString()))
				.andExpect(jsonPath("$.data.startedAt").isNotEmpty())
				.andExpect(jsonPath("$.data.endedAt").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		String liveId = JsonPath.read(body, "$.data.liveId");

		mockMvc.perform(get("/api/channels/" + channel + "/lives/current").session(login(mockMvc, "viewer")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.liveId").value(liveId));
		assertEquals(liveId, liveQuery.currentLive(channel).orElseThrow().liveId().toString());
	}

	@Test
	void streamerCanStartButViewerAndNonMemberCannot() throws Exception {
		mockMvc.perform(start(streamerChannel, login(mockMvc, "viewer"))).andExpect(status().isCreated());

		mockMvc.perform(start(channel, login(mockMvc, "viewer")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		mockMvc.perform(start(streamerChannel, login(mockMvc, "moderator")))
				.andExpect(status().isForbidden());
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform.lives WHERE channel_id = ?",
				Integer.class, channel));
	}

	@Test
	void secondStartWhileLiveIsOnIs409() throws Exception {
		MockHttpSession moderator = login(mockMvc, "moderator");
		mockMvc.perform(start(channel, moderator)).andExpect(status().isCreated());

		mockMvc.perform(start(channel, moderator))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("LIVE_ALREADY_ACTIVE"))
				.andExpect(jsonPath("$.error.retryable").value(false));
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM platform.lives WHERE channel_id = ?",
				Integer.class, channel));
	}

	@Test
	void endingPublishesOneEventAndEndingAgainIs409() throws Exception {
		MockHttpSession moderator = login(mockMvc, "moderator");
		String liveId = startLive(channel, moderator);

		mockMvc.perform(end(liveId, moderator))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.endedAt").isNotEmpty());
		mockMvc.perform(end(liveId, moderator))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("LIVE_ENDED"));

		assertEquals(1, recorder.events.size());
		assertEquals(liveId, recorder.events.getFirst().liveId().toString());
		assertEquals(channel, recorder.events.getFirst().channelId());
		mockMvc.perform(get("/api/channels/" + channel + "/lives/current").session(moderator))
				.andExpect(status().isNotFound());
		// 종료 후 새 방송을 시작할 수 있다
		mockMvc.perform(start(channel, moderator)).andExpect(status().isCreated());
	}

	@Test
	void viewerCannotEndAndUnknownLiveIs404() throws Exception {
		String liveId = startLive(channel, login(mockMvc, "moderator"));

		mockMvc.perform(end(liveId, login(mockMvc, "viewer"))).andExpect(status().isForbidden());
		mockMvc.perform(end(UUID.randomUUID().toString(), login(mockMvc, "moderator")))
				.andExpect(status().isNotFound());
		assertEquals(0, recorder.events.size());
	}

	@Test
	void currentLiveIsForMembersOnlyAnd404WhenNone() throws Exception {
		mockMvc.perform(get("/api/channels/" + channel + "/lives/current").session(login(mockMvc, "viewer")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
		mockMvc.perform(get("/api/channels/" + streamerChannel + "/lives/current")
						.session(login(mockMvc, "moderator")))
				.andExpect(status().isForbidden());
	}

	private String startLive(UUID channelId, MockHttpSession session) throws Exception {
		String body = mockMvc.perform(start(channelId, session)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.data.liveId");
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder start(UUID channelId,
			MockHttpSession session) throws Exception {
		return withXsrf(mockMvc, post("/api/channels/" + channelId + "/lives")).session(session);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder end(String liveId,
			MockHttpSession session) throws Exception {
		return withXsrf(mockMvc, post("/api/lives/" + liveId + "/end")).session(session);
	}

	@Component
	static class EndedRecorder {

		final List<LiveEndedEvent> events = new CopyOnWriteArrayList<>();

		@TransactionalEventListener
		void on(LiveEndedEvent event) {
			events.add(event);
		}

	}

}
