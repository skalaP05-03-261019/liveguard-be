package com.lifeguards.liveguard.guard.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
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
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.lifeguards.liveguard.TestcontainersConfiguration;
import com.lifeguards.liveguard.support.CaseFixtures;
import com.lifeguards.liveguard.support.MutableClock;
import com.sun.net.httpserver.HttpServer;

@SpringBootTest(properties = {
		"liveguard.agent.delivery.enabled=true", "liveguard.agent.delivery.transport=rabbit",
		"liveguard.agent.delivery.service-token=test-only",
		"liveguard.agent.delivery.poll-interval=1h",
		"liveguard.agent.delivery.rabbit.confirm-timeout=1s",
		"liveguard.agent.delivery.rabbit.publish-lease=3s",
		"liveguard.agent.delivery.rabbit.retry-interval=1s",
		"liveguard.agent.delivery.rabbit.recovery-interval=5s",
		"spring.rabbitmq.connection-timeout=1s"})
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AgentRabbitIntegrationTest.TestClock.class})
@Testcontainers
@DirtiesContext
class AgentRabbitIntegrationTest {
	private static final Instant NOW = Instant.parse("2026-10-21T04:00:00Z");
	private static final CopyOnWriteArrayList<String> METHODS = new CopyOnWriteArrayList<>();
	private static final CopyOnWriteArrayList<String> PAYLOADS = new CopyOnWriteArrayList<>();
	private static volatile AgentTaskEnvelope task;
	private static volatile int responseCode = 202;
	private static final HttpServer HTTP = startHttp();

	@Container
	static final GenericContainer<?> BROKER = new GenericContainer<>("rabbitmq:4.1-alpine")
			.withExposedPorts(5672);

	@DynamicPropertySource
	static void connection(DynamicPropertyRegistry registry) {
		registry.add("spring.rabbitmq.host", BROKER::getHost);
		registry.add("spring.rabbitmq.port", () -> BROKER.getMappedPort(5672));
		registry.add("liveguard.agent.delivery.base-url", () -> "http://127.0.0.1:" + HTTP.getAddress().getPort());
	}

	@TestConfiguration
	static class TestClock {
		@Bean
		@Primary
		MutableClock rabbitClock() {
			return new MutableClock(NOW);
		}
	}

	@Autowired AgentRabbitTransport transport;
	@Autowired SimpleMessageListenerContainer listener;
	@Autowired AgentRabbitProperties properties;
	@Autowired AgentOutboxRepository repository;
	@Autowired AgentTaskOutbox outbox;
	@Autowired JdbcTemplate jdbc;
	@Autowired TransactionTemplate transactions;
	@Autowired MutableClock clock;
	@Autowired ConnectionFactory connectionFactory;
	@Autowired ApplicationContext context;
	@Autowired tools.jackson.databind.json.JsonMapper mapper;
	private RabbitAdmin admin;
	private RabbitTemplate template;
	private UUID id;

	@BeforeEach
	void prepare() {
		listener.stop();
		admin = new RabbitAdmin(connectionFactory);
		template = new RabbitTemplate(connectionFactory);
		admin.purgeQueue(properties.queue());
		clock.setNow(NOW);
		METHODS.clear();
		PAYLOADS.clear();
		responseCode = 202;
		UUID caseId = UUID.randomUUID();
		CaseFixtures.insertCase(jdbc, caseId,
				UUID.fromString("20000000-0000-4000-8000-000000000001"),
				UUID.fromString("10000000-0000-4000-8000-000000000001"));
		UUID live = CaseFixtures.ensureLive(jdbc, UUID.fromString("20000000-0000-4000-8000-000000000001"));
		task = new AgentTaskEnvelope(UUID.randomUUID(), UUID.randomUUID(), caseId,
				AgentTaskEnvelope.Kind.ANALYZE, "thread-" + UUID.randomUUID(), UUID.randomUUID(),
				1, null, null, null, UUID.randomUUID(), live);
		jdbc.update("UPDATE guard.cases SET live_id=?,snapshot_id=? WHERE id=?", live, task.snapshotId(), caseId);
		jdbc.update("INSERT INTO guard.live_case_state(case_id,task_id,thread_id,snapshot) VALUES(?,?,?,'{}'::jsonb)",
				caseId, task.taskId(), task.threadId());
		id = transactions.execute(status -> outbox.enqueue(task));
	}

	@AfterEach
	void clean() {
		listener.stop();
		CaseFixtures.deleteCases(jdbc);
	}

	@AfterAll
	static void stopHttp() {
		HTTP.stop(0);
	}

	@Test
	void productionWiringPublishesMinimalPersistentHintsAndRecoversLostNotification() throws Exception {
		assertTrue(context.getBeansOfType(AgentDeliveryConfiguration.DeliveryScheduler.class).isEmpty());
		assertEquals(2, org.springframework.test.util.ReflectionTestUtils.getField(listener, "concurrentConsumers"));
		assertEquals(1, org.springframework.test.util.ReflectionTestUtils.getField(listener, "prefetchCount"));
		assertTrue(transport.publishOne());
		assertEquals("PENDING", status());
		assertEquals(0, attempts());
		assertTrue(METHODS.isEmpty());
		var notification = template.receive(properties.queue(), 5000);
		assertNotNull(notification);
		assertEquals(id.toString(), new String(notification.getBody(), StandardCharsets.US_ASCII));
		assertEquals(MessageDeliveryMode.PERSISTENT, notification.getMessageProperties().getReceivedDeliveryMode());
		assertFalse(transport.publishOne());
		clock.advance(properties.recoveryInterval());
		assertTrue(transport.publishOne());
		listener.start();
		await(() -> status().equals("ACCEPTED"));
		template.convertAndSend(properties.exchange(), properties.routingKey(), id.toString());
		await(() -> queueEmpty());
		assertEquals(1, attempts());
		assertEquals(java.util.List.of("POST"), METHODS);
		assertEquals(mapper.readTree(mapper.writeValueAsString(task)), mapper.readTree(PAYLOADS.getLast()));
		assertEquals(task.liveId().toString(), mapper.readTree(PAYLOADS.getLast()).path("liveId").stringValue());
	}

	@Test
	void legacyRabbitNotificationDoesNotEnrichStoredPayload() throws Exception {
		jdbc.update("UPDATE guard.agent_task_outbox SET payload=payload-'liveId' WHERE id=?", id);
		var stored = mapper.readTree(jdbc.queryForObject(
				"SELECT payload::text FROM guard.agent_task_outbox WHERE id=?", String.class, id));
		assertTrue(transport.publishOne());
		listener.start();
		await(() -> status().equals("ACCEPTED"));
		assertEquals(stored, mapper.readTree(PAYLOADS.getLast()));
		assertFalse(mapper.readTree(PAYLOADS.getLast()).has("liveId"));
	}

	@Test
	void unroutableAndBrokerRestartAreRecoveredWithoutHttpUncertainty() throws Exception {
		var binding = new Binding(properties.queue(), Binding.DestinationType.QUEUE,
				properties.exchange(), properties.routingKey(), null);
		admin.removeBinding(binding);
		try {
			assertTrue(transport.publishOne());
			assertEquals("PENDING", status());
			assertEquals(0, attempts());
			assertFalse(uncertain());
		} finally {
			admin.declareBinding(binding);
		}
		clock.advance(properties.retryInterval());
		assertTrue(transport.publishOne());
		assertEquals(0, BROKER.execInContainer("rabbitmqctl", "stop_app").getExitCode());
		clock.advance(properties.recoveryInterval());
		assertTrue(transport.publishOne());
		assertEquals(0, attempts());
		assertFalse(uncertain());
		assertEquals(0, BROKER.execInContainer("rabbitmqctl", "start_app").getExitCode());
		// The persistent hint survives a broker application restart.
		assertNotNull(template.receive(properties.queue(), 5000));
		clock.advance(properties.retryInterval());
		assertTrue(transport.publishOne());
		listener.start();
		await(() -> status().equals("ACCEPTED"));
		assertEquals(java.util.List.of("POST"), METHODS);
	}

	@Test
	void abandonedPublisherAndHttpConsumerLeasesRecoverWithDifferentSemantics() throws Exception {
		var abandoned = repository.claimPublication(clock.instant(), clock.instant().plus(properties.publishLease()))
				.orElseThrow();
		assertFalse(transport.publishOne());
		clock.advance(properties.publishLease());
		assertTrue(transport.publishOne());
		assertFalse(repository.finishPublication(abandoned, clock.instant().plusSeconds(100), clock.instant()));
		assertFalse(uncertain());
		// Simulate the consumer dying after HTTP reached the Agent but before DB completion/ACK.
		var delivery = repository.claim(id, clock.instant(), clock.instant().plusSeconds(60)).orElseThrow();
		assertTrue(context.getBean(AgentHttpGateway.class)
				.deliver(delivery.envelope(), false, true).accepted());
		clock.advance(Duration.ofSeconds(60));
		assertTrue(transport.publishOne());
		listener.start();
		await(() -> status().equals("ACCEPTED"));
		assertEquals(java.util.List.of("POST", "GET"), METHODS);
		assertTrue(uncertain());
		assertEquals(2, attempts());
	}

	@Test
	void known429UsesDirectPostAndPoisonDoesNotLoop() throws Exception {
		responseCode = 429;
		template.convertAndSend(properties.exchange(), properties.routingKey(), "poison");
		assertTrue(transport.publishOne());
		listener.start();
		await(() -> status().equals("RETRY"));
		assertFalse(uncertain());
		assertEquals(1, attempts());
		listener.stop();
		responseCode = 202;
		clock.advance(Duration.ofSeconds(2));
		assertTrue(transport.publishOne());
		listener.start();
		await(() -> status().equals("ACCEPTED"));
		await(this::queueEmpty);
		assertEquals(java.util.List.of("POST", "POST"), METHODS);
	}

  @Test
  void contendedHintWaitsForPublicationCommitThenDeliversWithoutRecoveryDelay() throws Exception {
    assertTrue(transport.publishOne());
    transactions.executeWithoutResult(tx->{
      jdbc.queryForList("SELECT id FROM guard.agent_task_outbox WHERE id=? FOR UPDATE",id);
      listener.start();
      try {await(this::queueEmpty);} catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}
      assertEquals(0,attempts());
      assertTrue(METHODS.isEmpty());
    });
    await(()->status().equals("ACCEPTED"));
    assertEquals(1,attempts());
    assertEquals(java.util.List.of("POST"),METHODS);
  }

	private boolean queueEmpty() {
		var info = admin.getQueueInfo(properties.queue());
		return info != null && info.getMessageCount() == 0;
	}

	private String status() {
		return jdbc.queryForObject("SELECT status FROM guard.agent_task_outbox WHERE id = ?", String.class, id);
	}

	private int attempts() {
		return jdbc.queryForObject("SELECT attempt_count FROM guard.agent_task_outbox WHERE id = ?", Integer.class, id);
	}

	private boolean uncertain() {
		return jdbc.queryForObject("SELECT delivery_uncertain FROM guard.agent_task_outbox WHERE id = ?", Boolean.class, id);
	}

	private void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.sleep(25);
		}
		assertTrue(condition.getAsBoolean(), "Timed out waiting for broker/DB processing");
	}

	private static HttpServer startHttp() {
		try {
			var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				METHODS.add(exchange.getRequestMethod());
				PAYLOADS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				String body = "{\"taskId\":\"" + task.taskId() + "\",\"threadId\":\"" + task.threadId()
						+ "\",\"operationId\":\"" + task.operationId() + "\",\"status\":\"QUEUED\""
						+ (exchange.getRequestMethod().equals("GET") ? "" : ",\"duplicate\":false") + "}";
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				try (exchange) {
					exchange.sendResponseHeaders(responseCode == 429 ? 429
							: exchange.getRequestMethod().equals("GET") ? 200 : 202, bytes.length);
					exchange.getResponseBody().write(bytes);
				}
			});
			server.start();
			return server;
		} catch (IOException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}
}
