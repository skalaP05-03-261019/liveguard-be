package com.lifeguards.liveguard.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
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
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.contracts.dto.event.ChatEvent;
import com.lifeguards.liveguard.contracts.enums.Visibility;

/** 실제 서버를 띄워 HTTP로 SSE를 구독한다 (브라우저 두 개로 CHAT을 받는 확인의 자동화). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "liveguard.sse.heartbeat-interval=200ms")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ChannelEventStreamTest {

	private static final UUID CHANNEL_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID CHANNEL_B = UUID.fromString("20000000-0000-4000-8000-000000000002");
	private static final UUID VIEWER = UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID VIEWER2 = UUID.fromString("10000000-0000-4000-8000-0000000000f2");
	private static final Duration WAIT = Duration.ofSeconds(5);

	@LocalServerPort
	private int port;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ChannelEventHub hub;

	private final List<Stream<String>> openStreams = new ArrayList<>();

	@BeforeEach
	void addSecondViewer() {
		String hash = jdbc.queryForObject("SELECT password_hash FROM platform.users WHERE username = 'viewer'",
				String.class);
		jdbc.update("INSERT INTO platform.users (id, username, password_hash, nickname, role) "
				+ "VALUES (?, 'viewer2', ?, '시청자2', 'VIEWER')", VIEWER2, hash);
		jdbc.update("INSERT INTO platform.channel_members (channel_id, user_id, member_role) VALUES (?, ?, 'VIEWER')",
				CHANNEL_A, VIEWER2);
	}

	@AfterEach
	void cleanUp() {
		openStreams.forEach(Stream::close);
		jdbc.update("DELETE FROM platform.messages");
		jdbc.update("DELETE FROM platform.channel_members WHERE user_id = ?", VIEWER2);
		jdbc.update("DELETE FROM platform.users WHERE id = ?", VIEWER2);
	}

	@Test
	void twoMembersReceiveChatWithIdAndConnectedComment() throws Exception {
		Client viewer = Client.login(port, "viewer");
		Client moderator = Client.login(port, "moderator");
		Lines viewerLines = subscribe(viewer, CHANNEL_A);
		Lines moderatorLines = subscribe(moderator, CHANNEL_A);
		viewerLines.await(":connected"::equals);
		moderatorLines.await(":connected"::equals);

		HttpResponse<String> sent = viewer.sendMessage(CHANNEL_A, "hello sse");
		assertEquals(201, sent.statusCode());

		for (Lines lines : List.of(viewerLines, moderatorLines)) {
			assertTrue(lines.await(line -> line.startsWith("id:")).matches("id:\\d+"));
			assertEquals("event:CHAT", lines.await(line -> line.startsWith("event:")));
			String data = lines.await(line -> line.startsWith("data:"));
			assertTrue(data.contains("\"text\":\"hello sse\""));
			assertTrue(data.contains("\"authorAlias\":\"시청자\""));
			assertTrue(data.contains("\"visibility\":\"PUBLIC\""));
		}
	}

	@Test
	void sendsHeartbeatComments() throws Exception {
		Lines lines = subscribe(Client.login(port, "viewer"), CHANNEL_A);

		lines.await(": ping"::equals);
		lines.await(": ping"::equals);
	}

	@Test
	void nonMemberIs403AndAnonymousIs401AsJson() throws Exception {
		HttpResponse<String> admin = Client.login(port, "admin").get("/api/channels/" + CHANNEL_A + "/events");
		assertEquals(403, admin.statusCode());
		assertTrue(admin.body().contains("\"code\":\"FORBIDDEN\""));

		HttpResponse<String> anonymous = new Client(port).get("/api/channels/" + CHANNEL_A + "/events");
		assertEquals(401, anonymous.statusCode());
		assertTrue(anonymous.body().contains("\"code\":\"UNAUTHENTICATED\""));
	}

	@Test
	void logoutClosesOnlyThatUsersStream() throws Exception {
		Client viewer = Client.login(port, "viewer");
		Lines viewerLines = subscribe(viewer, CHANNEL_A);
		Lines moderatorLines = subscribe(Client.login(port, "moderator"), CHANNEL_A);
		viewerLines.await(":connected"::equals);
		moderatorLines.await(":connected"::equals);

		assertEquals(204, viewer.post("/api/auth/logout", "").statusCode());

		viewerLines.awaitEnd();
		moderatorLines.await(": ping"::equals);
	}

	@Test
	void heldChatGoesOnlyToAuthorAndModerator() throws Exception {
		Lines viewerLines = subscribe(Client.login(port, "viewer"), CHANNEL_A);
		Lines viewer2Lines = subscribe(Client.login(port, "viewer2"), CHANNEL_A);
		Lines moderatorLines = subscribe(Client.login(port, "moderator"), CHANNEL_A);
		for (Lines lines : List.of(viewerLines, viewer2Lines, moderatorLines)) {
			lines.await(":connected"::equals);
		}

		hub.deliverChat(CHANNEL_A, VIEWER, chat("HELD-TEXT", Visibility.HELD));
		hub.deliverChat(CHANNEL_A, VIEWER, chat("public text", Visibility.PUBLIC));

		assertTrue(viewerLines.await(line -> line.startsWith("data:")).contains("HELD-TEXT"));
		assertTrue(moderatorLines.await(line -> line.startsWith("data:")).contains("HELD-TEXT"));
		// viewer2가 처음 받는 CHAT은 PUBLIC 메시지여야 한다 (HELD 원문을 받지 않음)
		assertTrue(viewer2Lines.await(line -> line.startsWith("data:")).contains("public text"));
	}

	@Test
	void stalledSubscriberDoesNotDelayPostResponse() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		BlockingQueue<String> received = new LinkedBlockingQueue<>();
		SseSubscriber stalled = new SseSubscriber(UUID.randomUUID(), true, "stalled", new SseEmitter(0L) {
			@Override
			public void send(SseEventBuilder builder) throws IOException {
				try {
					release.await();
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				builder.build().stream()
						.map(ResponseBodyEmitter.DataWithMediaType::getData)
						.filter(ChatEvent.class::isInstance)
						.map(data -> ((ChatEvent) data).text())
						.forEach(received::add);
			}
		});
		hub.register(CHANNEL_A, stalled);
		try {
			Client viewer = Client.login(port, "viewer");
			try {
				Instant start = Instant.now();
				// 로그인 직후 다른 GET 없이 바로 POST한다 (로그인 응답의 새 XSRF-TOKEN만으로 통과해야 한다)
				assertEquals(201, viewer.sendMessage(CHANNEL_A, "first").statusCode());
				assertEquals(201, viewer.sendMessage(CHANNEL_A, "second").statusCode());
				assertTrue(Duration.between(start, Instant.now()).compareTo(Duration.ofSeconds(2)) < 0,
						"멈춘 구독자가 있어도 POST 응답은 기다리지 않는다");
			} finally {
				release.countDown();
			}
			// 풀린 뒤에는 보낸 순서대로 전달된다
			assertEquals("first", awaitMatching(received, text -> true));
			assertEquals("second", awaitMatching(received, text -> true));
		} finally {
			hub.remove(CHANNEL_A, stalled);
		}
	}

	@Test
	void disconnectedSubscriberIsRemovedOnNextWrite() throws Exception {
		// 다른 테스트가 쓰지 않는 채널 B에서 확인한다
		int before = hub.subscriberCount(CHANNEL_B);
		Lines lines = subscribe(Client.login(port, "viewer"), CHANNEL_B);
		lines.await(":connected"::equals);
		assertEquals(before + 1, hub.subscriberCount(CHANNEL_B));

		lines.stream.close();

		Instant deadline = Instant.now().plus(WAIT);
		while (hub.subscriberCount(CHANNEL_B) > before && Instant.now().isBefore(deadline)) {
			Thread.sleep(50);
		}
		assertEquals(before, hub.subscriberCount(CHANNEL_B));
	}

	private Lines subscribe(Client client, UUID channelId) throws Exception {
		HttpResponse<Stream<String>> response = client.http.send(
				client.request("/api/channels/" + channelId + "/events").header("Accept", "text/event-stream").GET()
						.build(),
				HttpResponse.BodyHandlers.ofLines());
		assertEquals(200, response.statusCode());
		assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
		openStreams.add(response.body());
		return new Lines(response.body());
	}

	private static ChatEvent chat(String text, Visibility visibility) {
		return new ChatEvent(UUID.randomUUID(), "시청자", text, visibility, 1, Instant.now());
	}

	private static String awaitMatching(BlockingQueue<String> queue, Predicate<String> predicate)
			throws InterruptedException {
		Instant deadline = Instant.now().plus(WAIT);
		while (Instant.now().isBefore(deadline)) {
			String item = queue.poll(100, TimeUnit.MILLISECONDS);
			if (item != null && predicate.test(item)) {
				return item;
			}
		}
		throw new AssertionError("timed out");
	}

	/** SSE 응답 줄을 별도 스레드에서 읽어 큐에 쌓는다. 스트림이 끝나면 END를 넣는다. */
	static final class Lines {

		private static final String END = "\u0000END";

		private final Stream<String> stream;
		private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

		Lines(Stream<String> stream) {
			this.stream = stream;
			Thread reader = new Thread(() -> {
				try {
					stream.forEach(queue::add);
				} catch (RuntimeException ignored) {
					// 테스트가 스트림을 닫은 경우
				}
				queue.add(END);
			});
			reader.setDaemon(true);
			reader.start();
		}

		String await(Predicate<String> predicate) throws InterruptedException {
			String line = awaitMatching(queue, candidate -> candidate.equals(END) || predicate.test(candidate));
			assertNotNull(line);
			if (line.equals(END)) {
				throw new AssertionError("stream ended before the expected line");
			}
			return line;
		}

		void awaitEnd() throws InterruptedException {
			awaitMatching(queue, END::equals);
		}

	}

	/** 쿠키(세션, XSRF-TOKEN)를 유지하는 브라우저 대용 클라이언트. */
	static final class Client {

		private final int port;
		private final CookieManager cookies = new CookieManager();
		final HttpClient http;

		Client(int port) {
			this.port = port;
			this.http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(WAIT).build();
		}

		static Client login(int port, String username) throws Exception {
			Client client = new Client(port);
			client.get("/api/auth/me");
			HttpResponse<String> response = client.post("/api/auth/login",
					"{\"username\":\"" + username + "\",\"password\":\"liveguard-local\"}");
			assertEquals(200, response.statusCode(), response.body());
			return client;
		}

		HttpResponse<String> sendMessage(UUID channelId, String text) throws Exception {
			return post("/api/channels/" + channelId + "/messages",
					"{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"text\":\"" + text + "\"}");
		}

		HttpResponse<String> get(String path) throws Exception {
			return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
		}

		HttpResponse<String> post(String path, String json) throws Exception {
			HttpRequest.Builder builder = request(path).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(json));
			xsrfToken().ifPresent(token -> builder.header("X-XSRF-TOKEN", token));
			return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		}

		HttpRequest.Builder request(String path) {
			return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30));
		}

		private Optional<String> xsrfToken() {
			return cookies.getCookieStore().getCookies().stream()
					.filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
					.map(HttpCookie::getValue)
					.reduce((first, last) -> last);
		}

	}

}
