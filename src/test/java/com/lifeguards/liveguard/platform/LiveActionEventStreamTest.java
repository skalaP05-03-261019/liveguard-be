package com.lifeguards.liveguard.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.platform.ChannelEventStreamTest.Client;
import com.lifeguards.liveguard.platform.ChannelEventStreamTest.Lines;

/** 방송 채팅 SSE의 ACTION_APPLIED·ACTION_FAILED는 조치 대상 본인에게만 간다(계약). 운영자는 guard 스트림으로 받는다. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LiveActionEventStreamTest {

	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID VIEWER2 = UUID.fromString("10000000-0000-4000-8000-0000000000f4");

	@LocalServerPort
	private int port;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private LiveService lives;

	private final List<Stream<String>> openStreams = new ArrayList<>();
	private UUID liveId;

	@BeforeEach
	void setUp() {
		liveId = jdbc.queryForObject("SELECT id FROM platform.lives WHERE channel_id = ? AND ended_at IS NULL",
				UUID.class, CHANNEL_A);
		String hash = jdbc.queryForObject("SELECT password_hash FROM platform.users WHERE username = 'viewer'",
				String.class);
		jdbc.update("INSERT INTO platform.users (id, username, password_hash, nickname, role) "
				+ "VALUES (?, 'viewer4', ?, '시청자4', 'VIEWER')", VIEWER2, hash);
		jdbc.update("INSERT INTO platform.channel_members (channel_id, user_id, member_role) VALUES (?, ?, 'VIEWER')",
				CHANNEL_A, VIEWER2);
	}

	@AfterEach
	void cleanUp() {
		openStreams.forEach(Stream::close);
		jdbc.update("DELETE FROM platform.channel_members WHERE user_id = ?", VIEWER2);
		jdbc.update("DELETE FROM platform.users WHERE id = ?", VIEWER2);
	}

	@Test
	void actionEventsReachOnlyTheTargetOnTheChatStream() throws Exception {
		Lines target = subscribe(Client.login(port, "viewer"), "/api/lives/" + liveId + "/events");
		Lines otherViewer = subscribe(Client.login(port, "viewer4"), "/api/lives/" + liveId + "/events");
		Client moderator = Client.login(port, "moderator");
		Lines moderatorChat = subscribe(moderator, "/api/lives/" + liveId + "/events");
		Lines moderatorGuard = subscribe(moderator, "/api/guard/events?liveId=" + liveId);
		for (Lines lines : List.of(target, otherViewer, moderatorChat, moderatorGuard)) {
			lines.await(":connected"::equals);
		}

		for (String event : List.of("ACTION_APPLIED", "ACTION_FAILED")) {
			lives.emit(liveId, event, Map.of("actionId", UUID.randomUUID(), "liveId", liveId, "type", "TIMEOUT",
					"status", "SUCCEEDED", "observation", "APPLIED"), VIEWER, true);
			assertEquals("event:" + event, target.await(line -> line.startsWith("event:")));
			assertEquals("event:" + event, moderatorGuard.await(line -> line.startsWith("event:")));
		}

		for (Lines lines : List.of(otherViewer, moderatorChat)) {
			assertTrue(lines.drainFor(Duration.ofMillis(300)).stream().noneMatch(line -> line.startsWith("event:ACTION")));
		}
	}

	private Lines subscribe(Client client, String path) throws Exception {
		HttpResponse<Stream<String>> response = client.http.send(
				client.request(path).header("Accept", "text/event-stream").GET().build(),
				HttpResponse.BodyHandlers.ofLines());
		assertEquals(200, response.statusCode());
		openStreams.add(response.body());
		return new Lines(response.body());
	}

}
