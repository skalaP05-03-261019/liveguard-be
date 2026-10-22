package com.lifeguards.liveguard.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.support.CaseFixtures;
import com.lifeguards.liveguard.platform.ChannelEventStreamTest.Client;
import com.lifeguards.liveguard.platform.ChannelEventStreamTest.Lines;
import com.lifeguards.liveguard.platform.port.LivePlatformPort;

/** 방송 경로의 가시성 변경(LivePlatformPort.visibility)과 강퇴(LIVE_ACCESS_BLOCKED)가 기존 채널 SSE와 DB에 미치는 영향을 실제 서버로 확인한다. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "liveguard.sse.heartbeat-interval=200ms")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ChannelPublisherStreamTest {

	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID CHANNEL_B = UUID.fromString("20000000-0000-4000-8000-000000000002");
	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID VIEWER2 = UUID.fromString("10000000-0000-4000-8000-0000000000f4");
	private static final Instant T0 = Instant.parse("2026-10-20T00:00:00Z");
	private static final String MESSAGE_EVENT = "event:MESSAGE_VISIBILITY_CHANGED";

	@LocalServerPort
	private int port;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private LivePlatformPort platform;
	@Autowired
	private TransactionTemplate tx;

	private final List<Stream<String>> openStreams = new ArrayList<>();

	@BeforeEach
	void addSecondViewer() {
		String hash = jdbc.queryForObject("SELECT password_hash FROM platform.users WHERE username = 'viewer'",
				String.class);
		jdbc.update("INSERT INTO platform.users (id, username, password_hash, nickname, role) "
				+ "VALUES (?, 'viewer3', ?, '시청자2', 'VIEWER')", VIEWER2, hash);
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

	// ----- LivePlatformPort.visibility → 기존 채널 SSE(0.6 형태) -----
	// 사건이 소유한 메시지만 다룬다. 다른 원인이 남아 있으면 공개하지 않는 원인별 해제는 HoldReasonReleaseTest가 맡는다.

	@Test
	void hidingTellsTheChannelOnlyTheIdAndNeverTheText() throws Exception {
		UUID caseId = UUID.randomUUID();
		UUID message = insert(CHANNEL_A, VIEWER, "SECRET-ORIGINAL", "PUBLIC", caseId, 0);
		Streams streams = subscribeAll();

		change(caseId, "HIDDEN");

		for (Lines lines : streams.all()) {
			assertEquals(MESSAGE_EVENT, lines.await(line -> line.startsWith("event:")));
			String data = lines.await(line -> line.startsWith("data:"));
			assertTrue(data.contains("\"messageId\":\"" + message + "\""));
			assertTrue(data.contains("\"visibility\":\"HIDDEN\"") && data.contains("\"version\":2"));
			assertFalse(data.contains("SECRET-ORIGINAL") || data.contains("text") || data.contains("authorAlias")
					|| data.contains("sentAt"), data);
		}
		assertEquals(List.of("HIDDEN"), visibilitiesOf(message));
	}

	@Test
	void restoringSendsTheOriginalWithSentAtToEveryoneAndBringsItBackToHistory() throws Exception {
		UUID caseId = UUID.randomUUID();
		UUID message = insert(CHANNEL_A, VIEWER, "복원될 메시지", "HELD", caseId, 5);
		Streams streams = subscribeAll();
		Client otherViewer = Client.login(port, "viewer3");
		assertFalse(otherViewer.get("/api/channels/" + CHANNEL_A + "/messages").body().contains("복원될 메시지"));

		change(caseId, "PUBLIC");

		// 복원은 원문·작성자·보낸 시각을 담은 알림 하나다(ID만 담은 알림이 먼저 오지 않는다)
		for (Lines lines : streams.all()) {
			assertEquals(MESSAGE_EVENT, lines.await(line -> line.startsWith("event:")));
			String data = lines.await(line -> line.startsWith("data:"));
			assertTrue(data.contains("\"messageId\":\"" + message + "\""));
			assertTrue(data.contains("\"visibility\":\"PUBLIC\"") && data.contains("\"version\":2"));
			assertTrue(data.contains("\"text\":\"복원될 메시지\"") && data.contains("\"authorAlias\":\"시청자\""));
			assertTrue(data.contains("\"sentAt\":\"" + T0.plusSeconds(5) + "\""), data);
		}
		assertTrue(otherViewer.get("/api/channels/" + CHANNEL_A + "/messages").body().contains("복원될 메시지"));
		assertFalse(streams.author.drainFor(Duration.ofMillis(500)).stream().anyMatch(line -> line.startsWith("event:")));
	}

	@Test
	void hidingAHeldMessageOnlyReachesTheAuthorAndModerator() throws Exception {
		UUID heldCase = UUID.randomUUID();
		UUID sentinelCase = UUID.randomUUID();
		UUID held = insert(CHANNEL_A, VIEWER, "held text", "HELD", heldCase, 0);
		UUID sentinel = insert(CHANNEL_A, VIEWER, "sentinel", "PUBLIC", sentinelCase, 1);
		Streams streams = subscribeAll();

		change(heldCase, "HIDDEN");
		change(sentinelCase, "HIDDEN");

		// 작성자·운영자는 두 알림을 모두 받는다
		for (Lines lines : List.of(streams.author, streams.moderator)) {
			assertTrue(lines.await(line -> line.startsWith("data:")).contains(held.toString()));
			assertTrue(lines.await(line -> line.startsWith("data:")).contains(sentinel.toString()));
		}
		// 다른 시청자는 HELD 메시지를 본 적이 없으므로 그 알림도 받지 않는다. 첫 알림이 곧 sentinel이다
		assertTrue(streams.otherViewer.await(line -> line.startsWith("data:")).contains(sentinel.toString()));
	}

	@Test
	void liveStreamGetsOnlyMessageRestoredOnRestore() throws Exception {
		UUID caseId = UUID.randomUUID();
		UUID message = insert(CHANNEL_A, VIEWER, "방송 복원", "HELD", caseId, 6);
		Lines otherViewer = subscribePath(Client.login(port, "viewer3"), "/api/lives/" + liveOf(CHANNEL_A) + "/events");
		otherViewer.await(":connected"::equals);

		change(caseId, "PUBLIC");

		// 계약: PUBLIC 복원은 MESSAGE_RESTORED(ChatEvent)로 보낸다
		assertEquals("event:MESSAGE_RESTORED", otherViewer.await(line -> line.startsWith("event:")));
		assertTrue(otherViewer.await(line -> line.startsWith("data:")).contains(message.toString()));
		assertFalse(otherViewer.drainFor(Duration.ofMillis(500)).stream()
				.anyMatch(line -> line.equals("event:MESSAGE_VISIBILITY_CHANGED")));
	}

	@Test
	void changingToTheSameVisibilityAgainSendsNothingAndKeepsTheVersion() throws Exception {
		UUID caseId = UUID.randomUUID();
		UUID message = insert(CHANNEL_A, VIEWER, "h", "HELD", caseId, 0);
		Lines moderator = subscribe(Client.login(port, "moderator"), CHANNEL_A);
		moderator.await(":connected"::equals);

		change(caseId, "HIDDEN");
		change(caseId, "HIDDEN");

		assertEquals(2, versionOf(message));
		assertEquals(MESSAGE_EVENT, moderator.await(line -> line.startsWith("event:")));
		assertFalse(moderator.drainFor(Duration.ofMillis(500)).stream().anyMatch(line -> line.startsWith("event:MESSAGE")));
	}

	@Test
	void unknownCaseChangesNothing() {
		UUID other = insert(CHANNEL_A, VIEWER, "다른 사건", "HELD", UUID.randomUUID(), 0);

		change(UUID.randomUUID(), "HIDDEN");
		change(UUID.randomUUID(), "PUBLIC");

		assertEquals(List.of("HELD"), visibilitiesOf(other));
	}

	@Test
	void eventsAreSentOnlyAfterCommitAndNotOnRollbackAndTheChangeJoinsTheCallersTransaction() throws Exception {
		UUID rolledBackCase = UUID.randomUUID();
		UUID committedCase = UUID.randomUUID();
		UUID rolledBack = insert(CHANNEL_A, VIEWER, "롤백", "PUBLIC", rolledBackCase, 0);
		UUID committed = insert(CHANNEL_A, VIEWER, "커밋", "PUBLIC", committedCase, 1);
		Streams streams = subscribeAll();

		tx.executeWithoutResult(status -> {
			platform.visibility(rolledBackCase, "HIDDEN");
			status.setRollbackOnly();
		});
		assertEquals("PUBLIC", visibilitiesOf(rolledBack).getFirst(), "호출자 트랜잭션에 참여하므로 롤백되면 되돌아간다");
		assertEquals(1, versionOf(rolledBack));

		tx.executeWithoutResult(status -> {
			platform.visibility(committedCase, "HIDDEN");
			List<String> early = drain(streams.moderator, Duration.ofMillis(700));
			assertFalse(early.contains(MESSAGE_EVENT), "커밋 전에는 보내지 않는다");
		});

		// 롤백된 이벤트는 없고 커밋된 것만 온다
		assertEquals(MESSAGE_EVENT, streams.moderator.await(line -> line.startsWith("event:")));
		assertTrue(streams.moderator.await(line -> line.startsWith("data:")).contains(committed.toString()));
		assertFalse(streams.moderator.drainFor(Duration.ofMillis(500)).stream()
				.anyMatch(line -> line.contains(rolledBack.toString())));
	}

	@Test
	void eventsStayInTheirChannel() throws Exception {
		UUID caseInB = UUID.randomUUID();
		UUID caseInA = UUID.randomUUID();
		insert(CHANNEL_B, VIEWER, "채널 B", "PUBLIC", caseInB, 0);
		UUID inA = insert(CHANNEL_A, VIEWER, "채널 A", "PUBLIC", caseInA, 1);
		Streams streams = subscribeAll();

		change(caseInB, "HIDDEN");
		change(caseInA, "HIDDEN");

		assertTrue(streams.moderator.await(line -> line.startsWith("data:")).contains(inA.toString()));
	}

	// ----- 방송 강퇴(LIVE_ACCESS_BLOCKED) → 기존 채널 SSE의 USER_KICKED -----

	@Test
	void liveKickReachesAllConnectionsOfThatUserInThatChannelThenClosesThem() throws Exception {
		Lines viewerTab1 = subscribe(Client.login(port, "viewer"), CHANNEL_A);
		Lines viewerTab2 = subscribe(Client.login(port, "viewer"), CHANNEL_A);
		Lines viewerOtherChannel = subscribe(Client.login(port, "viewer"), CHANNEL_B);
		Lines otherViewer = subscribe(Client.login(port, "viewer3"), CHANNEL_A);
		Lines moderator = subscribe(Client.login(port, "moderator"), CHANNEL_A);
		for (Lines lines : List.of(viewerTab1, viewerTab2, viewerOtherChannel, otherViewer, moderator)) {
			lines.await(":connected"::equals);
		}

		tx.executeWithoutResult(status -> {
			accessBlocked(VIEWER);
			List<String> early = drain(viewerTab1, Duration.ofMillis(700));
			assertFalse(early.contains("event:USER_KICKED"), "커밋 전에는 보내지 않는다");
		});

		// 방송 종료까지라 until이 없다
		for (Lines tab : List.of(viewerTab1, viewerTab2)) {
			assertEquals("event:USER_KICKED", tab.await(line -> line.startsWith("event:")));
			assertEquals("data:{\"until\":null}", tab.await(line -> line.startsWith("data:")));
			tab.awaitEnd();
		}
		// 다른 사용자와 같은 사용자의 다른 채널 연결은 그대로 열려 있고 USER_KICKED를 받지 않는다
		for (Lines lines : List.of(otherViewer, moderator, viewerOtherChannel)) {
			List<String> seen = lines.drainFor(Duration.ofMillis(700));
			assertFalse(seen.contains("event:USER_KICKED"), seen.toString());
			assertTrue(seen.contains(": ping"), "연결이 유지되어 하트비트가 계속 온다: " + seen);
		}
	}

	@Test
	void rolledBackKickSendsNothingAndKeepsTheConnection() throws Exception {
		Lines viewer = subscribe(Client.login(port, "viewer"), CHANNEL_A);
		viewer.await(":connected"::equals);

		tx.executeWithoutResult(status -> {
			accessBlocked(VIEWER);
			status.setRollbackOnly();
		});

		List<String> seen = viewer.drainFor(Duration.ofMillis(700));
		assertFalse(seen.contains("event:USER_KICKED"));
		assertTrue(seen.contains(": ping"));
	}

	@Test
	void kickedUserCannotResubscribeOnceTheLiveKickIsCommitted() throws Exception {
		Client viewer = Client.login(port, "viewer");
		Lines stream = subscribe(viewer, CHANNEL_A);
		stream.await(":connected"::equals);

		tx.executeWithoutResult(status -> {
			CaseFixtures.insertLiveBan(jdbc, liveOf(CHANNEL_A), CHANNEL_A, VIEWER);
			accessBlocked(VIEWER);
		});

		stream.awaitEnd();
		HttpResponse<String> again = viewer.get("/api/channels/" + CHANNEL_A + "/events");
		assertEquals(403, again.statusCode());
		assertEquals("KICKED", JsonPath.read(again.body(), "$.error.code"));
		assertEquals(false, JsonPath.read(again.body(), "$.error.retryable"));
		// 방송 종료까지라 until이 없다
		assertFalse(again.body().contains("\"until\":\""), again.body());
	}

	// ----- helpers -----

	/**
	 * 강퇴 실행(LiveModerationService.execute)이 보내는 것과 같은 이벤트·payload. 기존 채널 SSE에는 대상 본인에게
	 * USER_KICKED로 간다. 실행 경로 자체는 LiveModerationIntegrationTest가 맡는다.
	 */
	private void accessBlocked(UUID userId) {
		UUID liveId = liveOf(CHANNEL_A);
		platform.emit(liveId, "LIVE_ACCESS_BLOCKED", Map.of("liveId", liveId, "activeUntilLiveEnd", true), userId, true);
	}

	private UUID liveOf(UUID channelId) {
		return CaseFixtures.ensureLive(jdbc, channelId);
	}

	private void change(UUID caseId, String visibility) {
		tx.executeWithoutResult(status -> platform.visibility(caseId, visibility));
	}

	private record Streams(Lines author, Lines otherViewer, Lines moderator) {

		List<Lines> all() {
			return List.of(author, otherViewer, moderator);
		}

	}

	private Streams subscribeAll() throws Exception {
		Streams streams = new Streams(subscribe(Client.login(port, "viewer"), CHANNEL_A),
				subscribe(Client.login(port, "viewer3"), CHANNEL_A),
				subscribe(Client.login(port, "moderator"), CHANNEL_A));
		for (Lines lines : streams.all()) {
			lines.await(":connected"::equals);
		}
		return streams;
	}

	private Lines subscribe(Client client, UUID channelId) throws Exception {
		return subscribePath(client, "/api/channels/" + channelId + "/events");
	}

	private Lines subscribePath(Client client, String path) throws Exception {
		HttpResponse<Stream<String>> response = client.http.send(
				client.request(path).header("Accept", "text/event-stream").GET().build(),
				HttpResponse.BodyHandlers.ofLines());
		assertEquals(200, response.statusCode());
		openStreams.add(response.body());
		return new Lines(response.body());
	}

	/** 같은 스레드(트랜잭션 안)에서 기다린 뒤 그동안 도착한 줄을 돌려준다. */
	private static List<String> drain(Lines lines, Duration duration) {
		try {
			return lines.drainFor(duration);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * 그 채널의 진행 중 방송(시드)에 속하고 사건이 소유한 메시지. 가시성 변경 알림은 방송 경로를 거쳐 채널 SSE로 간다.
	 * 사건을 연 메시지라 1차 필터는 HOLD다(PASS 메시지는 사건을 소유하지 않는다). 진행 중 방송이 없으면 바로 실패한다.
	 */
	private UUID insert(UUID channelId, UUID author, String text, String visibility, UUID caseId, int secondsAfterT0) {
		UUID liveId = liveOf(channelId);
		UUID id = UUID.randomUUID();
		CaseFixtures.insertCase(jdbc, caseId, channelId, author);
		jdbc.update("INSERT INTO platform.messages (id, channel_id, live_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, case_id, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'HOLD', ?, ?, ?)",
				id, channelId, liveId, author, UUID.randomUUID(), text, text, visibility, caseId,
				Timestamp.from(T0.plusSeconds(secondsAfterT0)));
		return id;
	}

	private int versionOf(UUID messageId) {
		return jdbc.queryForObject("SELECT version FROM platform.messages WHERE id = ?", Integer.class, messageId);
	}

	private List<String> visibilitiesOf(UUID... ids) {
		return Arrays.stream(ids).map(id -> jdbc.queryForObject(
				"SELECT visibility FROM platform.messages WHERE id = ?", String.class, id)).toList();
	}

}
