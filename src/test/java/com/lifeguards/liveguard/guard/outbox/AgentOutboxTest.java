package com.lifeguards.liveguard.guard.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;
import com.lifeguards.liveguard.guard.outbox.AgentTaskEnvelope.Kind;
import com.lifeguards.liveguard.guard.outbox.AgentTaskEnvelope.ReviewKind;
import com.lifeguards.liveguard.support.CaseFixtures;
import com.lifeguards.liveguard.support.MutableClock;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AgentOutboxTest.TestClock.class})
class AgentOutboxTest {

	private static final Instant NOW = Instant.parse("2026-10-21T04:00:00Z");
	private static final UUID CHANNEL = UUID.fromString("20000000-0000-4000-8000-000000000001");
	private static final UUID USER = UUID.fromString("10000000-0000-4000-8000-000000000001");

	@TestConfiguration
	static class TestClock {
		@Bean
		@Primary
		MutableClock outboxClock() {
			return new MutableClock(NOW);
		}
	}

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private AgentTaskOutbox outbox;
	@Autowired
	private AgentOutboxRepository repository;
	@Autowired
	private TransactionTemplate transactions;
	@Autowired
	private JsonMapper mapper;
	@Autowired
	private Clock clock;
	@Autowired
	private ApplicationContext context;

	private MutableClock time;
	private HttpServer server;
	private java.util.concurrent.ExecutorService httpExecutor;
	private AgentHttpGateway gateway;
	private AgentDeliveryWorker worker;
	private AgentDeliveryProperties properties;
	private AgentTaskEnvelope task;
	private final List<String> methods = new CopyOnWriteArrayList<>();
	private final List<String> payloads = new CopyOnWriteArrayList<>();
	private final List<String> paths = new CopyOnWriteArrayList<>();
	private final List<String> authorizations = new CopyOnWriteArrayList<>();
	private final List<String> traces = new CopyOnWriteArrayList<>();
	private volatile Consumer<HttpExchange> behavior;

	@BeforeEach
	void setUp() throws IOException {
		time = (MutableClock) clock;
		time.setNow(NOW);
		UUID caseId = UUID.randomUUID();
		CaseFixtures.insertCase(jdbc, caseId, CHANNEL, USER);
		task = task(caseId, Kind.ANALYZE, UUID.randomUUID(), "thread-" + UUID.randomUUID());
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		httpExecutor = Executors.newFixedThreadPool(2);
		server.setExecutor(httpExecutor);
		server.createContext("/", exchange -> {
			methods.add(exchange.getRequestMethod());
			paths.add(exchange.getRequestURI().getPath());
			authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
			traces.add(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
			payloads.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			behavior.accept(exchange);
		});
		server.start();
		properties = properties(3, Duration.ofSeconds(1));
		gateway = new AgentHttpGateway(properties, mapper);
		worker = new AgentDeliveryWorker(repository, gateway, properties, clock);
		behavior = exchange -> respond(exchange, 202, receipt(task, "QUEUED", false));
	}

	@AfterEach
	void cleanUp() {
		server.stop(0);
		httpExecutor.shutdownNow();
		gateway.close();
		CaseFixtures.deleteCases(jdbc);
	}

  @Test
  void addressedNotificationWaitsForPublicationRowLockInsteadOfAcknowledgingUndeliveredWork() throws Exception {
    UUID id=enqueue(task);
    var locked=new java.util.concurrent.CountDownLatch(1);
    var release=new java.util.concurrent.CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var holder=pool.submit(()->transactions.executeWithoutResult(tx->{
        jdbc.queryForObject("SELECT id FROM guard.agent_task_outbox WHERE id=? FOR UPDATE",UUID.class,id);
        locked.countDown();
        try {if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Lock release timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
      }));
      assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS));
      var delivered=pool.submit(()->worker.deliver(id));
      try {
        assertThrows(java.util.concurrent.TimeoutException.class,()->delivered.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));
      } finally {time.advance(Duration.ofSeconds(60));release.countDown();holder.get(5,java.util.concurrent.TimeUnit.SECONDS);}
      assertTrue(delivered.get(5,java.util.concurrent.TimeUnit.SECONDS));
      assertEquals("ACCEPTED",status(task));
      assertEquals(1,methods.size());
    }
  }

	@Test
	void enqueueRequiresABusinessTransactionAndRollsBackWithIt() {
		assertThrows(IllegalTransactionStateException.class, () -> outbox.enqueue(task));
		assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
			outbox.enqueue(task);
			assertEquals(1, count());
			assertTrue(methods.isEmpty());
			throw new IllegalStateException("Rollback business work");
		}));
		assertEquals(0, count());
		assertFalse(worker.deliverOne());
		assertTrue(methods.isEmpty());
	}

	@Test
	void newCaseAndItsOutboxEntryRollbackTogether() {
		UUID caseId = UUID.randomUUID();
		AgentTaskEnvelope candidate = task(caseId, Kind.ANALYZE, UUID.randomUUID(), "rollback-thread");
		assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
			CaseFixtures.insertCase(jdbc, caseId, CHANNEL, USER);
			outbox.enqueue(candidate);
			throw new IllegalStateException("Business operation failed");
		}));
		assertEquals(0, count());
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM guard.cases WHERE id = ?", Integer.class, caseId));
	}

	@Test
	void enqueueIsImmutableAndIdempotent() {
		UUID first = enqueue(task);
		assertEquals(first, enqueue(task));
		assertEquals(1, count());
		AgentTaskEnvelope changed = new AgentTaskEnvelope(UUID.randomUUID(), task.taskId(), task.caseId(),
				task.kind(), task.threadId(), task.snapshotId(), 2, null, null, null, task.traceId());
		assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT,
				assertThrows(ApiException.class, () -> enqueue(changed)).code());
		AgentTaskEnvelope reusedEvent = new AgentTaskEnvelope(task.eventId(), UUID.randomUUID(), task.caseId(),
				task.kind(), "new-thread", task.snapshotId(), 1, null, null, null, task.traceId());
		assertThrows(ApiException.class, () -> enqueue(reusedEvent));
		assertEquals(1, count());
	}

	@Test
	void runAndResumeShareTaskButHaveDistinctOperationsAndUseTheirCorrectEndpoints() {
		enqueue(task);
		assertTrue(worker.deliverOne());
		assertEquals("ACCEPTED", status(task));
		assertEquals(List.of("/agent/tasks/" + task.taskId() + "/run"), paths);
		AgentTaskEnvelope resume = task(task.caseId(), Kind.RESUME_REVIEW, task.taskId(), task.threadId());
		enqueue(resume);
		behavior = exchange -> respond(exchange, 202, receipt(resume, "QUEUED", false));
		worker.deliverOne();
		assertEquals("ACCEPTED", status(resume));
		assertEquals("/agent/tasks/" + task.taskId() + "/resume", paths.getLast());
		assertEquals(List.of("Bearer test-agent-secret", "Bearer test-agent-secret"), authorizations);
		assertEquals(List.of(task.traceId().toString(), resume.traceId().toString()), traces);
		assertEquals(mapper.readTree(mapper.writeValueAsString(resume)), mapper.readTree(payloads.getLast()));
	}

	@Test
	void twoClaimersNeverOwnTheSameRowAndUncommittedWorkCannotBeClaimed() throws Exception {
		transactions.executeWithoutResult(status -> {
			outbox.enqueue(task);
			try (var executor = Executors.newSingleThreadExecutor()) {
				try {
					assertTrue(executor.submit(() -> repository.claim(NOW, NOW.plusSeconds(60))).get().isEmpty());
				} catch (Exception ex) {
					throw new AssertionError(ex);
				}
			}
		});
		enqueue(task(task.caseId(), Kind.REANALYZE, UUID.randomUUID(), "another-thread"));
		try (var executor = Executors.newFixedThreadPool(2)) {
			var one = executor.submit(() -> repository.claim(NOW, NOW.plusSeconds(60)).orElseThrow());
			var two = executor.submit(() -> repository.claim(NOW, NOW.plusSeconds(60)).orElseThrow());
			assertNotEquals(one.get().id(), two.get().id());
		}
		assertTrue(repository.claim(NOW, NOW.plusSeconds(60)).isEmpty());
	}

	@Test
	void expiredLeaseCanBeRecoveredButOldOwnerCannotOverwrite() {
		enqueue(task);
		var first = repository.claim(NOW, NOW.plusSeconds(60)).orElseThrow();
		assertTrue(repository.claim(NOW.plusSeconds(59), NOW.plusSeconds(120)).isEmpty());
		assertFalse(repository.finish(first, "ACCEPTED", null, NOW, NOW.plusSeconds(60), false));
		var replacement = repository.claim(NOW.plusSeconds(60), NOW.plusSeconds(120)).orElseThrow();
		assertEquals(2, replacement.attemptCount());
		assertNotEquals(first.leaseToken(), replacement.leaseToken());
		assertFalse(repository.finish(first, "FAILED", "OLD_OWNER", NOW, NOW.plusSeconds(61), false));
		assertTrue(repository.finish(replacement, "ACCEPTED", null, NOW, NOW.plusSeconds(61), false));
		assertEquals("ACCEPTED", status(task));
	}

	@Test
	void crashAfterAgentAcceptedIsRecoveredByStatusWithoutAnotherPost() {
		enqueue(task);
		var lost = repository.claim(NOW, NOW.plusSeconds(60)).orElseThrow();
		assertTrue(gateway.deliver(lost.envelope(), false, true).accepted());
		assertEquals("IN_FLIGHT", status(task));
		time.advance(Duration.ofSeconds(60));
		behavior = exchange -> respond(exchange, 200, receipt(task, "WAITING_REVIEW", null));
		new AgentDeliveryWorker(repository, gateway, properties, clock).deliverOne();
		assertEquals(List.of("POST", "GET"), methods);
		assertEquals("ACCEPTED", status(task));
		assertEquals(2, attempts(task));
	}

	@Test
	void retryReusesExactPayloadAndHonorsNextAttemptTime() {
		enqueue(task);
		behavior = exchange -> respond(exchange, 503, "{}");
		worker.deliverOne();
		assertEquals("RETRY", status(task));
		assertFalse(worker.deliverOne());
		time.advance(properties.initialBackoff());
		behavior = exchange -> {
			if (exchange.getRequestMethod().equals("GET")) {
				respond(exchange, 404, "{}");
			} else {
				respond(exchange, 200, receipt(task, "RUNNING", true));
			}
		};
		worker.deliverOne();
		assertEquals("ACCEPTED", status(task));
		assertEquals(List.of("POST", "GET", "POST"), methods);
		assertEquals(payloads.getFirst(), payloads.getLast());
		assertEquals(2, attempts(task));
	}

	@Test
	void unreachableAttemptsDoNotSpendTheBudgetAndRecoveryKeepsTheFullBudget() throws Exception {
		enqueue(task);
		var unreachable = unreachableProperties(closedPort());
		try (var down = new AgentHttpGateway(unreachable, mapper)) {
			var downWorker = new AgentDeliveryWorker(repository, down, unreachable, clock);
			for (int i = 0; i < 6; i++) {
				downWorker.deliverOne();
				time.advance(Duration.ofMinutes(1));
			}
		}
		// 연결 거부는 요청이 닿지 않은 것이므로 예산(3)을 넘겨 시도해도 시도 수가 늘지 않고, 불확실 표시도 없다
		assertEquals("RETRY", status(task));
		assertEquals("AGENT_UNREACHABLE", errorCode(task));
		assertFalse(uncertain(task));
		assertEquals(0, attempts(task));
		// 다음 시도는 최대 간격(10초) 안이다
		assertTrue(jdbc.queryForObject("SELECT next_attempt_at - updated_at <= interval '10 seconds' "
				+ "FROM guard.agent_task_outbox WHERE operation_id = ?", Boolean.class, task.operationId()));

		// Agent가 돌아온 직후 일시 오류(429)가 나도 예산이 그대로라 끝나지 않는다
		behavior = exchange -> respond(exchange, 429, "{}");
		worker.deliverOne();
		assertEquals("RETRY", status(task));
		assertEquals(1, attempts(task));
		time.advance(Duration.ofMinutes(1));
		behavior = exchange -> respond(exchange, 202, receipt(task, "QUEUED", false));
		worker.deliverOne();
		assertEquals("ACCEPTED", status(task));
	}

	@Test
	void breakerSkipsTheNetworkAfterAConnectionFailureUntilTheBackoffPasses() throws Exception {
		int port = closedPort();
		try (var down = new AgentHttpGateway(unreachableProperties(port), mapper)) {
			assertTrue(down.deliver(task, false, true).notSent());
			AtomicInteger hits = new AtomicInteger();
			HttpServer revived = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
			revived.createContext("/", exchange -> {
				hits.incrementAndGet();
				respond(exchange, 202, receipt(task, "QUEUED", false));
			});
			revived.start();
			try {
				// 차단 중에는 Agent가 이미 떠 있어도 네트워크를 쓰지 않고 "닿지 않음"으로 답한다
				var skipped = down.deliver(task, false, true);
				assertTrue(skipped.notSent());
				assertEquals(0, hits.get());
				// 첫 차단 간격(initialBackoff 2초)이 지나면 다시 연결한다
				Thread.sleep(2100);
				assertTrue(down.deliver(task, false, true).accepted());
				assertEquals(1, hits.get());
			} finally {
				revived.stop(0);
			}
		}
	}

	@Test
	void exhaustedKnownRejectionsFailWithoutChangingKickExecutionBudget() {
		enqueue(task);
		behavior = exchange -> respond(exchange, exchange.getRequestMethod().equals("GET") ? 404 : 429, "{}");
		for (int i = 0; i < 3; i++) {
			worker.deliverOne();
			time.advance(Duration.ofMinutes(1));
		}
		assertEquals("FAILED", status(task));
		assertEquals(3, attempts(task));
		assertFalse(worker.deliverOne());
		assertEquals(3, methods.stream().filter("POST"::equals).count());
		assertEquals(List.of("POST", "POST", "POST"), methods);
	}

	@Test
	void specificNotificationsDoNotSpendAttemptsOnDuplicatesContentionOrFutureRetries() {
		UUID id = enqueue(task);
		assertTrue(worker.deliver(UUID.randomUUID()));
		var claimed = repository.claim(id, NOW, NOW.plusSeconds(60)).orElseThrow();
		assertTrue(worker.deliver(id));
		assertEquals(1, attempts(task));
		assertTrue(repository.finish(claimed, "RETRY", "HTTP_429", NOW.plusSeconds(10), NOW, false));
		assertTrue(worker.deliver(id));
		assertEquals(1, attempts(task));
		time.advance(Duration.ofSeconds(10));
		assertTrue(worker.deliver(id));
		assertTrue(worker.deliver(id));
		assertEquals(2, attempts(task));
		assertEquals(List.of("POST"), methods);
	}

	@Test
	void publishLeaseRecoveryIsFencedAndDoesNotChangeHttpUncertaintyOrBudget() {
		UUID id = enqueue(task);
		var first = repository.claimPublication(NOW, NOW.plusSeconds(30)).orElseThrow();
		assertEquals(id, first.id());
		assertTrue(repository.claimPublication(NOW.plusSeconds(29), NOW.plusSeconds(60)).isEmpty());
		var replacement = repository.claimPublication(NOW.plusSeconds(30), NOW.plusSeconds(60)).orElseThrow();
		assertNotEquals(first.token(), replacement.token());
		assertFalse(repository.finishPublication(first, NOW.plusSeconds(100), NOW.plusSeconds(31)));
		assertTrue(repository.finishPublication(replacement, NOW.plusSeconds(90), NOW.plusSeconds(31)));
		assertTrue(repository.claimPublication(NOW.plusSeconds(89), NOW.plusSeconds(120)).isEmpty());
		assertTrue(repository.claimPublication(NOW.plusSeconds(90), NOW.plusSeconds(120)).isPresent());
		assertEquals(0, attempts(task));
		assertFalse(jdbc.queryForObject("SELECT delivery_uncertain FROM guard.agent_task_outbox WHERE id = ?",
				Boolean.class, id));
	}

	@Test
	void httpClaimFencesPublisherAndRetryBecomesNotifiableOnlyWhenDue() {
		UUID id = enqueue(task);
		var publisher = repository.claimPublication(NOW, NOW.plusSeconds(30)).orElseThrow();
		behavior = exchange -> respond(exchange, 429, "{}");
		worker.deliver(id);
		assertFalse(repository.finishPublication(publisher, NOW.plusSeconds(100), NOW));
		assertTrue(repository.claimPublication(NOW, NOW.plusSeconds(30)).isEmpty());
		time.advance(properties.initialBackoff());
		assertTrue(repository.claimPublication(time.instant(), time.instant().plusSeconds(30)).isPresent());
		assertEquals(List.of("POST"), methods);
	}

	@Test
	void permanentHttpErrorsAreNotRetried() {
		for (int code : List.of(400, 401, 403, 409, 422, 302)) {
			AgentTaskEnvelope candidate = task(task.caseId(), Kind.ANALYZE, UUID.randomUUID(), "thread-" + code);
			enqueue(candidate);
			behavior = exchange -> respond(exchange, code, "{}");
			worker.deliverOne();
			assertEquals("FAILED", status(candidate));
			assertEquals(1, attempts(candidate));
		}
		assertEquals(6, methods.size());
	}

	@Test
	void invalidReceiptsNeverLookLikeAcceptedWork() {
		List<String> invalid = new ArrayList<>();
		invalid.add("not-json");
		invalid.add("{}");
		invalid.add(receipt(task, "QUEUED", true));
		invalid.add(receipt(task, "INVENTED_STATE", false));
		invalid.add(receipt(task, "QUEUED", false).replace(task.taskId().toString(), UUID.randomUUID().toString()));
		invalid.add(receipt(task, "QUEUED", false).replace(task.operationId(), "resume:" + UUID.randomUUID()));
		for (String body : invalid) {
			behavior = exchange -> respond(exchange, 202, body);
			var result = gateway.deliver(task, false, true);
			assertFalse(result.accepted());
			assertTrue(result.uncertain());
		}
	}

	@Test
	void timeoutIncludingSlowBodyAndMalformedResponsesEndAsUnknownAtBudgetLimit() throws Exception {
		enqueue(task);
		AtomicInteger calls = new AtomicInteger();
		behavior = exchange -> {
			calls.incrementAndGet();
			try {
				exchange.sendResponseHeaders(202, 100);
				Thread.sleep(400);
				exchange.close();
			} catch (IOException ex) {
				exchange.close();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				exchange.close();
			}
		};
		try (var timedGateway = new AgentHttpGateway(properties(1, Duration.ofMillis(100)), mapper)) {
			var timedWorker = new AgentDeliveryWorker(repository, timedGateway,
					properties(1, Duration.ofMillis(100)), clock);
			timedWorker.deliverOne();
			assertEquals("UNKNOWN", status(task));
			assertEquals(1, calls.get());
		}
	}

	@Test
	void resumeRecoveryDoesNotMistakeAnEarlierOperationForItsReceipt() {
		AgentTaskEnvelope resume = task(task.caseId(), Kind.RESUME_REVIEW, task.taskId(), task.threadId());
		behavior = exchange -> {
			if (exchange.getRequestMethod().equals("GET")) {
				respond(exchange, 200, receipt(task, "WAITING_REVIEW", null));
			} else {
				respond(exchange, 202, receipt(resume, "QUEUED", false));
			}
		};
		assertTrue(gateway.deliver(resume, true, true).accepted());
		assertEquals(List.of("GET", "POST"), methods);
	}

	@Test
	void exhaustedCrashRecoveryCanConfirmAcceptanceButCannotSendAnotherPost() {
		enqueue(task);
		for (int i = 0; i < 3; i++) {
			repository.claim(time.instant(), time.instant().plusSeconds(60)).orElseThrow();
			time.advance(Duration.ofSeconds(60));
		}
		behavior = exchange -> respond(exchange, 404, "{}");
		worker.deliverOne();
		assertEquals("UNKNOWN", status(task));
		assertEquals(List.of("GET"), methods);
		assertEquals(4, attempts(task));
	}

	@Test
	void receiptMeansInboxAcceptanceEvenWhenAgentExecutionAlreadyFailed() {
		enqueue(task);
		behavior = exchange -> respond(exchange, 202, receipt(task, "FAILED", false));
		worker.deliverOne();
		assertEquals("ACCEPTED", status(task));
	}

	@Test
	void schedulerIsDisabledByDefault() {
		assertTrue(context.getBeansOfType(AgentDeliveryWorker.class).isEmpty());
		assertTrue(context.getBeansOfType(AgentHttpGateway.class).isEmpty());
	}

	@Test
	void networkDeliveryCannotRunInsideTheBusinessTransaction() {
		enqueue(task);
		assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status ->
				worker.deliverOne()));
		assertEquals("PENDING", status(task));
		assertTrue(methods.isEmpty());
	}

	@Test
	void aPriorUncertainAttemptIsNotTurnedIntoConfirmedFailureByLaterRejection() {
		enqueue(task);
		behavior = exchange -> respond(exchange, 202, "{}");
		worker.deliverOne();
		time.advance(properties.initialBackoff());
		behavior = exchange -> respond(exchange, exchange.getRequestMethod().equals("GET") ? 404 : 401, "{}");
		worker.deliverOne();
		assertEquals("UNKNOWN", status(task));
	}

	@Test
	void oversizedResponseIsBoundedAndNeverAccepted() {
		behavior = exchange -> respond(exchange, 202, "x".repeat(70000));
		var result = gateway.deliver(task, false, true);
		assertFalse(result.accepted());
		assertTrue(result.uncertain());
	}

	@Test
	void unknownRecoveryWithMatchingIdentityCanFinishAfterThePostBudgetWasSpent() {
		enqueue(task);
		for (int i = 0; i < properties.maxAttempts(); i++) {
			repository.claim(time.instant(), time.instant().plusSeconds(60)).orElseThrow();
			time.advance(Duration.ofSeconds(60));
		}
		behavior = exchange -> respond(exchange, 200, receipt(task, "SUCCEEDED", null));
		worker.deliverOne();
		assertEquals("ACCEPTED", status(task));
		assertEquals(List.of("GET"), methods);
	}

	private UUID enqueue(AgentTaskEnvelope envelope) {
		return transactions.execute(status -> outbox.enqueue(envelope));
	}

	private int count() {
		return jdbc.queryForObject("SELECT count(*) FROM guard.agent_task_outbox", Integer.class);
	}

	private String status(AgentTaskEnvelope envelope) {
		return jdbc.queryForObject("SELECT status FROM guard.agent_task_outbox WHERE operation_id = ?",
				String.class, envelope.operationId());
	}

	/** 지금 아무도 쓰지 않는 로컬 포트(연결 거부). */
	private static int closedPort() throws IOException {
		try (var socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static AgentDeliveryProperties unreachableProperties(int port) {
		return new AgentDeliveryProperties(true, URI.create("http://127.0.0.1:" + port), "test-agent-secret",
				Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(1),
				Duration.ofSeconds(2), Duration.ofSeconds(10), 3, 5, 2);
	}

	private String errorCode(AgentTaskEnvelope envelope) {
		return jdbc.queryForObject("SELECT last_error_code FROM guard.agent_task_outbox WHERE operation_id = ?",
				String.class, envelope.operationId());
	}

	private boolean uncertain(AgentTaskEnvelope envelope) {
		return jdbc.queryForObject("SELECT delivery_uncertain FROM guard.agent_task_outbox WHERE operation_id = ?",
				Boolean.class, envelope.operationId());
	}

	private int attempts(AgentTaskEnvelope envelope) {
		return jdbc.queryForObject("SELECT attempt_count FROM guard.agent_task_outbox WHERE operation_id = ?",
				Integer.class, envelope.operationId());
	}

	private AgentTaskEnvelope task(UUID caseId, Kind kind, UUID taskId, String thread) {
		return new AgentTaskEnvelope(UUID.randomUUID(), taskId, caseId, kind, thread, UUID.randomUUID(), 1,
				kind == Kind.RESUME_REVIEW ? UUID.randomUUID() : null,
				kind == Kind.RESUME_REVIEW ? ReviewKind.CONTEXT : null,
				kind == Kind.RESUME_REVIEW ? UUID.randomUUID() : null, UUID.randomUUID());
	}

	private AgentDeliveryProperties properties(int maxAttempts, Duration timeout) {
		return new AgentDeliveryProperties(true, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
				"test-agent-secret", Duration.ofMillis(100), timeout, Duration.ofSeconds(60),
				Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10),
				maxAttempts, 5, 2);
	}

	private String receipt(AgentTaskEnvelope task, String state, Boolean duplicate) {
		return "{\"taskId\":\"" + task.taskId() + "\",\"threadId\":\"" + task.threadId()
				+ "\",\"operationId\":\"" + task.operationId() + "\",\"status\":\"" + state + "\""
				+ (duplicate == null ? "" : ",\"duplicate\":" + duplicate) + "}";
	}

	private void respond(HttpExchange exchange, int code, String body) {
		try (exchange) {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(code, bytes.length);
			exchange.getResponseBody().write(bytes);
		} catch (IOException ex) {
			throw new AssertionError("HTTP fixture response failed", ex);
		}
	}
}
