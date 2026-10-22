package com.lifeguards.liveguard.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

import com.jayway.jsonpath.JsonPath;
import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.platform.ChannelEventStreamTest.Client;
import com.lifeguards.liveguard.platform.ChannelEventStreamTest.Lines;
import com.lifeguards.liveguard.support.CaseFixtures;

/** 방송 채팅 SSE(/api/lives/{liveId}/events)의 CHAT payload는 계약 ChatEvent의 필드만 싣는다. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LiveChatEventStreamTest {

	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID VIEWER2 = UUID.fromString("10000000-0000-4000-8000-0000000000f3");
	private static final Set<String> CHAT_EVENT_FIELDS = Set.of("messageId", "liveId", "authorAlias", "text",
			"visibility", "version", "sentAt");

	@LocalServerPort
	private int port;
	@Autowired
	private JdbcTemplate jdbc;

	private final List<Stream<String>> openStreams = new ArrayList<>();
	private UUID liveId;

	@BeforeEach
	void setUp() {
		liveId = jdbc.queryForObject("SELECT id FROM platform.lives WHERE channel_id = ? AND ended_at IS NULL",
				UUID.class, CHANNEL_A);
		String hash = jdbc.queryForObject("SELECT password_hash FROM platform.users WHERE username = 'viewer'",
				String.class);
		jdbc.update("INSERT INTO platform.users (id, username, password_hash, nickname, role) "
				+ "VALUES (?, 'viewer3', ?, '시청자3', 'VIEWER')", VIEWER2, hash);
		jdbc.update("INSERT INTO platform.channel_members (channel_id, user_id, member_role) VALUES (?, ?, 'VIEWER')",
				CHANNEL_A, VIEWER2);
	}

	@AfterEach
	void cleanUp() {
		openStreams.forEach(Stream::close);
		CaseFixtures.deleteCases(jdbc);
		jdbc.update("DELETE FROM platform.messages");
		jdbc.update("DELETE FROM platform.channel_members WHERE user_id = ?", VIEWER2);
		jdbc.update("DELETE FROM platform.users WHERE id = ?", VIEWER2);
	}

	@Test
	void publicChatPayloadHasOnlyChatEventFieldsForEveryone() throws Exception {
		Client author = Client.login(port, "viewer");
		List<Lines> subscribers = List.of(subscribe(author), subscribe(Client.login(port, "viewer3")),
				subscribe(Client.login(port, "moderator")));
		for (Lines lines : subscribers) {
			lines.await(":connected"::equals);
		}

		assertEquals(201, send(author, "안녕하세요 방송").statusCode());

		// 다른 시청자에게 isMine=true가 가거나, 작성자 응답 전용 필드가 섞이면 안 된다
		for (Lines lines : subscribers) {
			Map<String, Object> payload = chatPayload(lines);
			assertEquals(CHAT_EVENT_FIELDS, payload.keySet(), payload.toString());
			assertEquals("안녕하세요 방송", payload.get("text"));
		}
	}

	@Test
	void heldChatPayloadCarriesNoAuthorOnlyFieldsToTheModerator() throws Exception {
		Client author = Client.login(port, "viewer");
		Lines authorLines = subscribe(author);
		Lines moderatorLines = subscribe(Client.login(port, "moderator"));
		authorLines.await(":connected"::equals);
		moderatorLines.await(":connected"::equals);

		HttpResponse<String> sent = send(author, "무대 미쳤다ㅋㅋ");
		assertEquals(201, sent.statusCode());
		// 작성자 본인 응답에는 안내 사유가 있다
		assertTrue(sent.body().contains("\"moderationReasonCode\":\"CONTEXT_PENDING\""), sent.body());

		for (Lines lines : List.of(authorLines, moderatorLines)) {
			Map<String, Object> payload = chatPayload(lines);
			assertEquals(CHAT_EVENT_FIELDS, payload.keySet(), payload.toString());
			assertEquals("HELD", payload.get("visibility"));
			assertFalse(payload.containsKey("moderationNotice"));
		}
	}

	private Lines subscribe(Client client) throws Exception {
		HttpResponse<Stream<String>> response = client.http.send(
				client.request("/api/lives/" + liveId + "/events").header("Accept", "text/event-stream").GET().build(),
				HttpResponse.BodyHandlers.ofLines());
		assertEquals(200, response.statusCode());
		openStreams.add(response.body());
		return new Lines(response.body());
	}

	private HttpResponse<String> send(Client client, String text) throws Exception {
		return client.post("/api/lives/" + liveId + "/messages",
				"{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"text\":\"" + text + "\"}");
	}

	private static Map<String, Object> chatPayload(Lines lines) throws InterruptedException {
		lines.await("event:CHAT"::equals);
		String data = lines.await(line -> line.startsWith("data:")).substring("data:".length());
		return JsonPath.read(data, "$.payload");
	}

}
