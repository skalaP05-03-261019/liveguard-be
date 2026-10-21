package com.lifeguards.liveguard.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import com.lifeguards.liveguard.support.CaseFixtures;
import com.lifeguards.liveguard.platform.ChannelEventStreamTest.Client;
import com.lifeguards.liveguard.platform.ChannelEventStreamTest.Lines;
import com.lifeguards.liveguard.platform.port.ChannelEventPublisher;
import com.lifeguards.liveguard.platform.port.InMemoryKickStatus;
import com.lifeguards.liveguard.platform.port.KickStatusQuery;
import com.lifeguards.liveguard.platform.port.MessageVisibilityPort;

/** B2가 쓰는 포트(MessageVisibilityPort, ChannelEventPublisher)가 채팅 SSE와 DB에 미치는 영향을 실제 서버로 확인한다. */
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
	private MessageVisibilityPort visibility;
	@Autowired
	private ChannelEventPublisher publisher;
	@Autowired
	private KickStatusQuery kickStatus;
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
		((InMemoryKickStatus) kickStatus).clear();
		jdbc.update("DELETE FROM platform.messages");
		CaseFixtures.deleteCases(jdbc);
		jdbc.update("DELETE FROM platform.channel_members WHERE user_id = ?", VIEWER2);
		jdbc.update("DELETE FROM platform.users WHERE id = ?", VIEWER2);
	}

	// ----- MessageVisibilityPort -----

	@Test
	void hidingAPublicMessageTellsEveryoneOnlyTheIdAndNeverTheText() throws Exception {
		UUID caseId = UUID.randomUUID();
		UUID message = insert(CHANNEL_A, VIEWER, "SECRET-ORIGINAL", "PUBLIC", caseId, 0);
		Streams streams = subscribeAll();

		assertEquals(1, hide(caseId));

		for (Lines lines : streams.all()) {
			assertEquals(MESSAGE_EVENT, lines.await(line -> line.startsWith("event:")));
			String data = lines.await(line -> line.startsWith("data:"));
			assertTrue(data.contains("\"messageId\":\"" + message + "\""));
			assertTrue(data.contains("\"visibility\":\"HIDDEN\"") && data.contains("\"version\":2"));
			assertFalse(data.contains("SECRET-ORIGINAL") || data.contains("text") || data.contains("authorAlias")
					|| data.contains("sentAt"), data);
		}
		assertEquals("HIDDEN", jdbc.queryForObject("SELECT visibility FROM platform.messages WHERE id = ?",
				String.class, message));
	}

	@Test
	void hidingAHeldMessageOnlyReachesTheAuthorAndModerator() throws Exception {
		UUID heldCase = UUID.randomUUID();
		UUID sentinelCase = UUID.randomUUID();
		insert(CHANNEL_A, VIEWER, "held text", "HELD", heldCase, 0);
		UUID sentinel = insert(CHANNEL_A, VIEWER, "sentinel", "PUBLIC", sentinelCase, 1);
		Streams streams = subscribeAll();

		tx.executeWithoutResult(status -> visibility.hideCaseMessages(heldCase));
		tx.executeWithoutResult(status -> visibility.hideCaseMessages(sentinelCase));

		// 작성자·운영자는 두 이벤트를 모두 받는다
		for (Lines lines : List.of(streams.author, streams.moderator)) {
			assertEquals(2, countEvents(lines, 2));
		}
		// 다른 시청자는 HELD였던 메시지를 받은 적이 없으므로 첫 이벤트가 곧 sentinel이다
		assertTrue(streams.otherViewer.await(line -> line.startsWith("data:")).contains(sentinel.toString()));
	}

	@Test
	void restoringSendsTheOriginalWithSentAtToEveryoneAndBringsItBackToHistory() throws Exception {
		UUID caseId = UUID.randomUUID();
		UUID message = insert(CHANNEL_A, VIEWER, "복원될 메시지", "HELD", caseId, 5);
		Streams streams = subscribeAll();
		Client otherViewer = Client.login(port, "viewer3");
		assertFalse(otherViewer.get("/api/channels/" + CHANNEL_A + "/messages").body().contains("복원될 메시지"));

		assertEquals(1, restore(caseId));

		for (Lines lines : streams.all()) {
			assertEquals(MESSAGE_EVENT, lines.await(line -> line.startsWith("event:")));
			String data = lines.await(line -> line.startsWith("data:"));
			assertTrue(data.contains("\"messageId\":\"" + message + "\""));
			assertTrue(data.contains("\"visibility\":\"PUBLIC\"") && data.contains("\"version\":2"));
			assertTrue(data.contains("\"text\":\"복원될 메시지\"") && data.contains("\"authorAlias\":\"시청자\""));
			assertTrue(data.contains("\"sentAt\":\"" + T0.plusSeconds(5) + "\""), data);
		}
		assertTrue(otherViewer.get("/api/channels/" + CHANNEL_A + "/messages").body().contains("복원될 메시지"));
	}

	@Test
	void hideAndRestoreSkipMessagesAlreadyInTheTargetStateAndCountOnlyChanges() {
		UUID caseId = UUID.randomUUID();
		UUID publicMessage = insert(CHANNEL_A, VIEWER, "p", "PUBLIC", caseId, 0);
		UUID heldMessage = insert(CHANNEL_A, VIEWER, "h", "HELD", caseId, 1);
		UUID hiddenMessage = insert(CHANNEL_A, VIEWER, "x", "HIDDEN", caseId, 2);

		assertEquals(2, restore(caseId), "PUBLIC은 건너뛴다");
		assertEquals(1, versionOf(publicMessage));
		assertEquals(2, versionOf(heldMessage));
		assertEquals(2, versionOf(hiddenMessage));
		assertEquals(0, restore(caseId), "이미 그 상태면 0");

		assertEquals(3, hide(caseId));
		assertEquals(0, hide(caseId));
		assertEquals(2, versionOf(publicMessage));
		assertEquals(3, versionOf(heldMessage));
		assertEquals(3, versionOf(hiddenMessage));
	}

	@Test
	void floodCaseHidesOnlyTheThirdMessageAndKeepsEarlierEvidencePublicAndLinked() throws Exception {
		UUID caseId = UUID.randomUUID();
		UUID evidence1 = insert(CHANNEL_A, VIEWER, "도배", "PUBLIC", "PASS", caseId, 0);
		UUID evidence2 = insert(CHANNEL_A, VIEWER, "도배", "PUBLIC", "PASS", caseId, 1);
		UUID third = insert(CHANNEL_A, VIEWER, "도배", "HELD", "FLOOD", caseId, 2);
		Streams streams = subscribeAll();

		assertEquals(1, hide(caseId), "세 번째 이후(FLOOD)만 바뀐다");

		assertEquals(List.of("PUBLIC", "PUBLIC", "HIDDEN"), visibilitiesOf(evidence1, evidence2, third));
		assertEquals(List.of(1, 1, 2), List.of(versionOf(evidence1), versionOf(evidence2), versionOf(third)));
		assertEquals(List.of(caseId, caseId, caseId), jdbc.queryForList(
				"SELECT case_id FROM platform.messages ORDER BY sent_at, id", UUID.class), "증거 연결은 유지된다");
		// 세 번째는 HELD였으므로 작성자·운영자만 이벤트를 받고, 증거 메시지에 대한 이벤트는 없다
		for (Lines lines : List.of(streams.author, streams.moderator)) {
			assertTrue(lines.await(line -> line.startsWith("data:")).contains(third.toString()));
			List<String> more = lines.drainFor(Duration.ofMillis(500));
			assertFalse(more.stream().anyMatch(line -> line.startsWith("event:MESSAGE")), more.toString());
		}
		assertFalse(streams.otherViewer.drainFor(Duration.ofMillis(500)).stream()
				.anyMatch(line -> line.startsWith("event:MESSAGE")));

		assertEquals(1, restore(caseId), "복원은 HELD·HIDDEN만 되돌린다");
		assertEquals(List.of("PUBLIC", "PUBLIC", "PUBLIC"), visibilitiesOf(evidence1, evidence2, third));
	}

	@Test
	void messagesThatPassedTheFilterAreNeverHiddenButReviewMessagesAre() {
		UUID caseId = UUID.randomUUID();
		UUID passed = insert(CHANNEL_A, VIEWER, "통과", "PUBLIC", "PASS", caseId, 0);
		UUID review = insert(CHANNEL_A, VIEWER, "미쳤다", "PUBLIC", "REVIEW", caseId, 1);
		UUID hold = insert(CHANNEL_A, VIEWER, "씨발", "HELD", "HOLD", caseId, 2);

		assertEquals(2, hide(caseId));

		assertEquals(List.of("PUBLIC", "HIDDEN", "HIDDEN"), visibilitiesOf(passed, review, hold));
		assertEquals(0, hide(caseId));
	}

	@Test
	void unknownCaseChangesNothing() {
		assertEquals(0, hide(UUID.randomUUID()));
		assertEquals(0, restore(UUID.randomUUID()));
	}

	@Test
	void eventsAreSentOnlyAfterCommitAndNotOnRollbackAndTheChangeJoinsTheCallersTransaction() throws Exception {
		UUID rolledBackCase = UUID.randomUUID();
		UUID committedCase = UUID.randomUUID();
		UUID rolledBack = insert(CHANNEL_A, VIEWER, "롤백", "PUBLIC", rolledBackCase, 0);
		UUID committed = insert(CHANNEL_A, VIEWER, "커밋", "PUBLIC", committedCase, 1);
		Streams streams = subscribeAll();

		tx.executeWithoutResult(status -> {
			visibility.hideCaseMessages(rolledBackCase);
			status.setRollbackOnly();
		});
		assertEquals("PUBLIC", visibilitiesOf(rolledBack).getFirst(), "호출자 트랜잭션에 참여하므로 롤백되면 되돌아간다");
		assertEquals(1, versionOf(rolledBack));

		tx.executeWithoutResult(status -> {
			visibility.hideCaseMessages(committedCase);
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

		tx.executeWithoutResult(status -> visibility.hideCaseMessages(caseInB));
		tx.executeWithoutResult(status -> visibility.hideCaseMessages(caseInA));

		assertTrue(streams.moderator.await(line -> line.startsWith("data:")).contains(inA.toString()));
	}

	// ----- ChannelEventPublisher.userKicked -----

	@Test
	void userKickedReachesAllConnectionsOfThatUserInThatChannelThenClosesThem() throws Exception {
		Instant until = Instant.parse("2026-10-21T12:00:00Z");
		Lines viewerTab1 = subscribe(Client.login(port, "viewer"), CHANNEL_A);
		Lines viewerTab2 = subscribe(Client.login(port, "viewer"), CHANNEL_A);
		Lines viewerOtherChannel = subscribe(Client.login(port, "viewer"), CHANNEL_B);
		Lines otherViewer = subscribe(Client.login(port, "viewer3"), CHANNEL_A);
		Lines moderator = subscribe(Client.login(port, "moderator"), CHANNEL_A);
		for (Lines lines : List.of(viewerTab1, viewerTab2, viewerOtherChannel, otherViewer, moderator)) {
			lines.await(":connected"::equals);
		}

		tx.executeWithoutResult(status -> {
			publisher.userKicked(CHANNEL_A, VIEWER, until);
			List<String> early = drain(viewerTab1, Duration.ofMillis(700));
			assertFalse(early.contains("event:USER_KICKED"), "커밋 전에는 보내지 않는다");
		});

		for (Lines tab : List.of(viewerTab1, viewerTab2)) {
			assertEquals("event:USER_KICKED", tab.await(line -> line.startsWith("event:")));
			assertEquals("data:{\"until\":\"2026-10-21T12:00:00Z\"}", tab.await(line -> line.startsWith("data:")));
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
			publisher.userKicked(CHANNEL_A, VIEWER, Instant.parse("2026-10-21T12:00:00Z"));
			status.setRollbackOnly();
		});

		List<String> seen = viewer.drainFor(Duration.ofMillis(700));
		assertFalse(seen.contains("event:USER_KICKED"));
		assertTrue(seen.contains(": ping"));
	}

	@Test
	void kickedUserCannotResubscribeOnceTheKickIsVisibleToKickStatusQuery() throws Exception {
		Client viewer = Client.login(port, "viewer");
		Lines stream = subscribe(viewer, CHANNEL_A);
		stream.await(":connected"::equals);
		Instant until = Instant.now().plus(Duration.ofHours(24));
		InMemoryKickStatus kicks = assertInstanceOf(InMemoryKickStatus.class, kickStatus);

		tx.executeWithoutResult(status -> {
			kicks.kick(CHANNEL_A, VIEWER, until);
			publisher.userKicked(CHANNEL_A, VIEWER, until);
		});

		stream.awaitEnd();
		HttpResponse<String> again = viewer.get("/api/channels/" + CHANNEL_A + "/events");
		assertEquals(403, again.statusCode());
		assertTrue(again.body().contains("\"code\":\"KICKED\""));
	}

	// ----- helpers -----

	private int hide(UUID caseId) {
		return tx.execute(status -> visibility.hideCaseMessages(caseId));
	}

	private int restore(UUID caseId) {
		return tx.execute(status -> visibility.restoreCaseMessages(caseId));
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
		HttpResponse<Stream<String>> response = client.http.send(
				client.request("/api/channels/" + channelId + "/events").header("Accept", "text/event-stream").GET()
						.build(),
				HttpResponse.BodyHandlers.ofLines());
		assertEquals(200, response.statusCode());
		openStreams.add(response.body());
		return new Lines(response.body());
	}

	private int countEvents(Lines lines, int expected) throws Exception {
		int count = 0;
		for (int i = 0; i < expected; i++) {
			lines.await(line -> line.startsWith("data:"));
			count++;
		}
		return count;
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

	/** 가림 대상이 되는 사건 메시지: HELD는 HOLD, 그 외는 REVIEW로 저장한다 (PASS는 가림 대상이 아니다). */
	private UUID insert(UUID channelId, UUID author, String text, String visibility, UUID caseId, int secondsAfterT0) {
		String filterResult = visibility.equals("HELD") ? "HOLD" : "REVIEW";
		return insert(channelId, author, text, visibility, filterResult, caseId, secondsAfterT0);
	}

	private UUID insert(UUID channelId, UUID author, String text, String visibility, String filterResult,
			UUID caseId, int secondsAfterT0) {
		UUID id = UUID.randomUUID();
		CaseFixtures.insertCase(jdbc, caseId, channelId, author);
		jdbc.update("INSERT INTO platform.messages (id, channel_id, author_id, client_message_id, text, "
				+ "normalized_text, filter_result, visibility, case_id, sent_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				id, channelId, author, UUID.randomUUID(), text, text, filterResult, visibility, caseId,
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
