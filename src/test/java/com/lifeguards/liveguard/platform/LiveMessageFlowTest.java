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

/** 채팅의 방송 귀속(A-17, A-18). 시드 채널의 방송은 건드리지 않도록 전용 채널을 쓴다. */
@SpringBootTest(properties = { "LIVEGUARD_POLICY_VERSION=v3.1", "LIVEGUARD_TBD_FIXTURE_ENABLED=true" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LiveMessageFlowTest {

	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID MODERATOR = UUID.fromString("10000000-0000-4000-8000-000000000002");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;

	private UUID channel;
	private MockHttpSession viewer;
	private MockHttpSession moderator;

	@BeforeEach
	void setUp() throws Exception {
		channel = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.channels (id, name) VALUES (?, 'live-flow')", channel);
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
	void messageSentToALiveBelongsToThatLive() throws Exception {
		String liveId = startLive();

		mockMvc.perform(sendToLive(liveId, UUID.randomUUID(), "안녕하세요"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.liveId").value(liveId))
				.andExpect(jsonPath("$.data.channelId").value(channel.toString()));

		assertEquals(liveId, jdbc.queryForObject("SELECT live_id::text FROM platform.messages WHERE channel_id = ?",
				String.class, channel));
	}

	@Test
	void sendingToAnEndedLiveIs409AndStoresNothing() throws Exception {
		String liveId = startLive();
		endLive(liveId);

		mockMvc.perform(sendToLive(liveId, UUID.randomUUID(), "끝난 방송"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("LIVE_ENDED"))
				.andExpect(jsonPath("$.error.retryable").value(false));
		assertEquals(0, messageCount());
	}

	@Test
	void replayOfAMessageStoredBeforeTheEndReturnsTheFirstResponse() throws Exception {
		String liveId = startLive();
		UUID clientMessageId = UUID.randomUUID();
		// 확실한 욕설이라 사건도 함께 열린다
		String first = mockMvc.perform(sendToLive(liveId, clientMessageId, "씨발"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		int casesBefore = caseCount();
		endLive(liveId);

		String replay = mockMvc.perform(sendToLive(liveId, clientMessageId, "씨발"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

		assertEquals(JsonPath.<Object>read(first, "$.data"), JsonPath.<Object>read(replay, "$.data"));
		assertEquals(1, messageCount());
		assertEquals(1, casesBefore);
		assertEquals(casesBefore, caseCount());
	}

	@Test
	void legacyChannelPathIsDisabledInIntegrationMode() throws Exception {
		mockMvc.perform(sendToChannel(UUID.randomUUID(), "방송 전"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("CASE_NOT_READY"));

		startLive();
		mockMvc.perform(sendToChannel(UUID.randomUUID(), "방송 중"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("CASE_NOT_READY"));
		assertEquals(0, messageCount());
	}

	@Test
	void unknownLiveIs404AndNonMemberIs403() throws Exception {
		mockMvc.perform(sendToLive(UUID.randomUUID().toString(), UUID.randomUUID(), "어디"))
				.andExpect(status().isNotFound());

		UUID otherChannel = UUID.randomUUID();
		UUID otherLive = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.channels (id, name) VALUES (?, 'not-joined')", otherChannel);
		jdbc.update("INSERT INTO platform.lives (id, channel_id, started_at) VALUES (?, ?, now())", otherLive, otherChannel);
		try {
			mockMvc.perform(sendToLive(otherLive.toString(), UUID.randomUUID(), "남의 방송"))
					.andExpect(status().isForbidden());
		} finally {
			jdbc.update("DELETE FROM platform.lives WHERE id = ?", otherLive);
			jdbc.update("DELETE FROM platform.channels WHERE id = ?", otherChannel);
		}
	}

	@Test
	void liveHistoryIsScopedAndChannelHistoryIsDisabledInIntegrationMode() throws Exception {
		String first = startLive();
		mockMvc.perform(sendToLive(first, UUID.randomUUID(), "첫 방송")).andExpect(status().isCreated());
		endLive(first);
		String second = startLive();
		mockMvc.perform(sendToLive(second, UUID.randomUUID(), "둘째 방송")).andExpect(status().isCreated());

		String live = mockMvc.perform(get("/api/lives/" + second + "/messages").session(viewer))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		mockMvc.perform(get("/api/channels/" + channel + "/messages").session(viewer))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("CASE_NOT_READY"));

		assertEquals(List.of("둘째 방송"), JsonPath.read(live, "$.data.items[*].text"));
	}

	private String startLive() throws Exception {
		String body = mockMvc.perform(withXsrf(mockMvc, post("/api/channels/" + channel + "/lives")).session(moderator))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.data.liveId");
	}

	private void endLive(String liveId) throws Exception {
		mockMvc.perform(withXsrf(mockMvc, post("/api/lives/" + liveId + "/end")).session(moderator))
				.andExpect(status().isOk());
	}

	private MockHttpServletRequestBuilder sendToLive(String liveId, UUID clientMessageId, String text) throws Exception {
		return withXsrf(mockMvc, post("/api/lives/" + liveId + "/messages")).session(viewer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"" + clientMessageId + "\",\"text\":\"" + text + "\"}");
	}

	private MockHttpServletRequestBuilder sendToChannel(UUID clientMessageId, String text) throws Exception {
		return withXsrf(mockMvc, post("/api/channels/" + channel + "/messages")).session(viewer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"" + clientMessageId + "\",\"text\":\"" + text + "\"}");
	}

	private int messageCount() {
		return jdbc.queryForObject("SELECT count(*) FROM platform.messages WHERE channel_id = ?", Integer.class, channel);
	}

	private int caseCount() {
		return jdbc.queryForObject("""
				SELECT count(DISTINCT cm.case_id) FROM guard.case_messages cm
				JOIN platform.messages m ON m.id = cm.message_id WHERE m.channel_id = ?""", Integer.class, channel);
	}

}
