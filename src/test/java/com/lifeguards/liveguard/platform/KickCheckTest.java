package com.lifeguards.liveguard.platform;

import static com.lifeguards.liveguard.support.LoginSupport.login;
import static com.lifeguards.liveguard.support.LoginSupport.withXsrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.test.web.servlet.ResultActions;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.support.CaseFixtures;

/**
 * 방송 강퇴(guard.live_kicks): 그 방송이 끝날 때까지 그 사용자의 채팅을 막는다(POLICY §10). 방송 종료까지라
 * KICKED에는 until이 없다. 방송 종료 뒤 새 방송에서 다시 채팅할 수 있는지는 LiveModerationIntegrationTest가 맡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class KickCheckTest {

	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID CHANNEL_B = UUID.fromString("20000000-0000-4000-8000-000000000002");
	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID ADMIN = UUID.fromString("10000000-0000-4000-8000-000000000003");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;

	private UUID liveA;

	/** 같은 컨텍스트의 다른 테스트가 시드 방송을 지울 수 있으므로, 진행 중 방송이 없으면 만든다. */
	@BeforeEach
	void ensureLives() {
		liveA = CaseFixtures.ensureLive(jdbc, CHANNEL_A);
		CaseFixtures.ensureLive(jdbc, CHANNEL_B);
	}

	@AfterEach
	void cleanUp() {
		CaseFixtures.deleteCases(jdbc);
		jdbc.update("DELETE FROM platform.messages");
	}

	@Test
	void kickedViewerGets403KickedWithoutUntilOnBothSendPaths() throws Exception {
		CaseFixtures.insertLiveBan(jdbc, liveA, CHANNEL_A, VIEWER);
		MockHttpSession viewer = login(mockMvc, "viewer");

		for (String path : List.of("/api/lives/" + liveA + "/messages", "/api/channels/" + CHANNEL_A + "/messages")) {
			send(path, UUID.randomUUID(), viewer)
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.error.code").value("KICKED"))
					.andExpect(jsonPath("$.error.retryable").value(false))
					.andExpect(jsonPath("$.error.until").doesNotExist())
					.andExpect(jsonPath("$.traceId").isNotEmpty());
		}

		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM platform.messages", Integer.class));
	}

	@Test
	void kickAppliesOnlyToThatUserAndLive() throws Exception {
		CaseFixtures.insertLiveBan(jdbc, liveA, CHANNEL_A, VIEWER);

		send("/api/channels/" + CHANNEL_A + "/messages", UUID.randomUUID(), login(mockMvc, "moderator"))
				.andExpect(status().isCreated());
		send("/api/channels/" + CHANNEL_B + "/messages", UUID.randomUUID(), login(mockMvc, "viewer"))
				.andExpect(status().isCreated());
	}

	@Test
	void kickEndsWithTheLiveAndDoesNotCarryOverToTheNextLiveOfTheChannel() throws Exception {
		CaseFixtures.insertLiveBan(jdbc, liveA, CHANNEL_A, VIEWER);
		MockHttpSession viewer = login(mockMvc, "viewer");
		String path = "/api/channels/" + CHANNEL_A + "/messages";
		send(path, UUID.randomUUID(), viewer).andExpect(status().isForbidden());

		// 같은 채널이라도 새 방송에는 강퇴가 이어지지 않는다(POLICY §10)
		jdbc.update("UPDATE platform.lives SET ended_at = now() WHERE id = ?", liveA);
		UUID next = CaseFixtures.ensureLive(jdbc, CHANNEL_A);

		send("/api/lives/" + next + "/messages", UUID.randomUUID(), viewer).andExpect(status().isCreated());
		send(path, UUID.randomUUID(), viewer).andExpect(status().isCreated());
	}

	@Test
	void replayOfAnAlreadyStoredMessageIsAlsoBlockedWhileKicked() throws Exception {
		MockHttpSession viewer = login(mockMvc, "viewer");
		UUID clientMessageId = UUID.randomUUID();
		String path = "/api/channels/" + CHANNEL_A + "/messages";
		send(path, clientMessageId, viewer).andExpect(status().isCreated());
		CaseFixtures.insertLiveBan(jdbc, liveA, CHANNEL_A, VIEWER);

		// 재전송 멱등보다 강퇴 확인이 먼저다(A-18)
		send(path, clientMessageId, viewer).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("KICKED"));
	}

	@Test
	void nonMemberGets403ForbiddenEvenIfKicked() throws Exception {
		CaseFixtures.insertLiveBan(jdbc, liveA, CHANNEL_A, ADMIN);

		// 비회원에게 강퇴 여부가 드러나지 않도록 회원 확인이 먼저다
		send("/api/channels/" + CHANNEL_A + "/messages", UUID.randomUUID(), login(mockMvc, "admin"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	private ResultActions send(String path, UUID clientMessageId, MockHttpSession session) throws Exception {
		return mockMvc.perform(withXsrf(mockMvc, post(path))
				.session(session)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"" + clientMessageId + "\",\"text\":\"안녕하세요\"}"));
	}

}
