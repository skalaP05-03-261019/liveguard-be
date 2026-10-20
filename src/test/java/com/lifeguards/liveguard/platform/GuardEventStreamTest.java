package com.lifeguards.liveguard.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.contracts.dto.event.CaseEvent;
import com.lifeguards.liveguard.contracts.enums.CaseStatus;
import com.lifeguards.liveguard.platform.ChannelEventStreamTest.Client;
import com.lifeguards.liveguard.platform.ChannelEventStreamTest.Lines;
import com.lifeguards.liveguard.platform.port.GuardEventPublisher;

/** 관제 SSE: Agent 진행 이벤트가 내부 API → 담당 운영자 콘솔까지 오는지 실제 서버로 확인한다. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "liveguard.sse.heartbeat-interval=200ms")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class GuardEventStreamTest {

	private static final String TOKEN = "test-service-token-for-tests-only";
	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID CHANNEL_B = UUID.fromString("20000000-0000-4000-8000-000000000002");
	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");

	@LocalServerPort
	private int port;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private GuardEventPublisher publisher;
	@Autowired
	private TransactionTemplate transactionTemplate;

	private final List<Stream<String>> openStreams = new ArrayList<>();
	private UUID caseId;

	@BeforeEach
	void insertCaseMessage() {
		caseId = UUID.randomUUID();
		jdbc.update("INSERT INTO platform.messages (channel_id, author_id, client_message_id, text, normalized_text, "
				+ "filter_result, visibility, case_id, sent_at) VALUES (?, ?, ?, '증거', '증거', 'REVIEW', 'PUBLIC', ?, ?)",
				CHANNEL_A, VIEWER, UUID.randomUUID(), caseId, Timestamp.from(Instant.now()));
	}

	@AfterEach
	void cleanUp() {
		openStreams.forEach(Stream::close);
		jdbc.update("DELETE FROM platform.messages");
	}

	@Test
	void agentToolEventsReachTheModeratorConsoleInOrder() throws Exception {
		Lines moderator = subscribe(Client.login(port, "moderator"), CHANNEL_A);
		moderator.await(":connected"::equals);

		assertEquals(202, postInternalEvent("{\"event\":\"TOOL_START\",\"payload\":{\"tool\":\"get_chat_context\","
				+ "\"callId\":\"c1\",\"args\":{\"q\":\"RAW-ARGUMENT\"}}}"));
		assertEquals(202, postInternalEvent("{\"event\":\"TOOL_END\",\"payload\":{\"tool\":\"get_chat_context\","
				+ "\"callId\":\"c1\",\"status\":\"OK\",\"elapsedMs\":42}}"));

		assertEquals("event:TOOL_START", moderator.await(line -> line.startsWith("event:")));
		String start = moderator.await(line -> line.startsWith("data:"));
		assertTrue(start.contains("\"caseId\":\"" + caseId + "\""));
		assertTrue(start.contains("\"tool\":\"get_chat_context\""));
		assertTrue(start.contains("\"callId\":\"c1\""));
		assertFalse(start.contains("RAW-ARGUMENT"));
		assertEquals("event:TOOL_END", moderator.await(line -> line.startsWith("event:")));
		String end = moderator.await(line -> line.startsWith("data:"));
		assertTrue(end.contains("\"status\":\"OK\"") && end.contains("\"elapsedMs\":42"));
	}

	@Test
	void onlyChannelModeratorsCanSubscribe() throws Exception {
		for (String username : new String[] { "viewer", "admin" }) {
			HttpResponse<String> response = Client.login(port, username).get("/api/guard/events?channelId=" + CHANNEL_A);
			assertEquals(403, response.statusCode(), username);
			assertTrue(response.body().contains("\"code\":\"FORBIDDEN\""));
		}
		assertEquals(401, new Client(port).get("/api/guard/events?channelId=" + CHANNEL_A).statusCode());
		assertEquals(400, Client.login(port, "moderator").get("/api/guard/events").statusCode());
		assertEquals(400, Client.login(port, "moderator").get("/api/guard/events?channelId=nope").statusCode());
	}

	@Test
	void eventsStayInTheirChannel() throws Exception {
		Lines channelA = subscribe(Client.login(port, "moderator"), CHANNEL_A);
		channelA.await(":connected"::equals);

		publisher.publish(CHANNEL_B, "CASE_CREATED", caseEvent("B"));
		publisher.publish(CHANNEL_A, "CASE_CREATED", caseEvent("A"));

		String data = channelA.await(line -> line.startsWith("data:"));
		assertTrue(data.contains(caseEvent("A").caseId().toString()), "채널 B 이벤트는 오지 않는다");
	}

	@Test
	void publishInsideATransactionIsSentOnlyAfterCommit() throws Exception {
		Lines moderator = subscribe(Client.login(port, "moderator"), CHANNEL_A);
		moderator.await(":connected"::equals);
		CaseEvent rolledBack = caseEvent("rolled-back");
		CaseEvent committed = caseEvent("committed");

		transactionTemplate.executeWithoutResult(tx -> {
			publisher.publish(CHANNEL_A, "CASE_UPDATED", rolledBack);
			tx.setRollbackOnly();
		});
		transactionTemplate.executeWithoutResult(tx -> publisher.publish(CHANNEL_A, "CASE_UPDATED", committed));

		String data = moderator.await(line -> line.startsWith("data:"));
		assertTrue(data.contains(committed.caseId().toString()), "롤백된 이벤트는 보내지 않는다");
	}

	@Test
	void unknownEventNameIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> publisher.publish(CHANNEL_A, "CASE_CREATD", caseEvent("typo")));
		assertThrows(IllegalArgumentException.class, () -> publisher.publish(CHANNEL_A, "CHAT", caseEvent("chat")));
	}

	@Test
	void heartbeatAndLogoutCloseBehaveLikeTheChatStream() throws Exception {
		Client moderatorClient = Client.login(port, "moderator");
		Lines moderator = subscribe(moderatorClient, CHANNEL_A);
		moderator.await(": ping"::equals);

		assertEquals(204, moderatorClient.post("/api/auth/logout", "").statusCode());

		moderator.awaitEnd();
	}

	private CaseEvent caseEvent(String seed) {
		return new CaseEvent(UUID.nameUUIDFromBytes(seed.getBytes()), CaseStatus.ANALYZING, null, 1);
	}

	private int postInternalEvent(String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/cases/"
				+ caseId + "/events"))
				.header("Content-Type", "application/json")
				.header("X-Service-Token", TOKEN)
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	private Lines subscribe(Client client, UUID channelId) throws Exception {
		HttpResponse<Stream<String>> response = client.http.send(
				client.request("/api/guard/events?channelId=" + channelId).header("Accept", "text/event-stream").GET()
						.build(),
				HttpResponse.BodyHandlers.ofLines());
		assertEquals(200, response.statusCode());
		openStreams.add(response.body());
		return new Lines(response.body());
	}

}
