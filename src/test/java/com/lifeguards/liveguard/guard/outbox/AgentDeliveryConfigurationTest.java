package com.lifeguards.liveguard.guard.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import com.lifeguards.liveguard.guard.outbox.AgentTaskEnvelope.Kind;
import com.lifeguards.liveguard.guard.outbox.AgentTaskEnvelope.ReviewKind;

import tools.jackson.databind.json.JsonMapper;

class AgentDeliveryConfigurationTest {

	@Test
	void enabledConfigurationCreatesWorkerAndDisablingItCreatesNoNetworkClients() {
		var runner = new ApplicationContextRunner()
				.withUserConfiguration(AgentDeliveryConfiguration.class)
				.withBean(AgentOutboxRepository.class, () -> mock(AgentOutboxRepository.class))
				.withBean(Clock.class, Clock::systemUTC)
				.withBean(JsonMapper.class, () -> JsonMapper.builder().build());
		runner.run(context -> {
			assertTrue(context.getBeansOfType(AgentHttpGateway.class).isEmpty());
			assertTrue(context.getBeansOfType(AgentDeliveryWorker.class).isEmpty());
		});
		runner.withPropertyValues("liveguard.agent.delivery.enabled=true",
				"liveguard.agent.delivery.base-url=http://127.0.0.1:1",
				"liveguard.agent.delivery.service-token=test-only",
				"liveguard.agent.delivery.poll-interval=1h").run(context -> {
					assertNotNull(context.getBean(AgentDeliveryWorker.class));
					assertNotNull(context.getBean(AgentDeliveryConfiguration.DeliveryScheduler.class));
				});
	}

	@Test
	void configurationRejectsMissingCredentialsAndUnsafeOrInvalidLimits() {
		assertThrows(IllegalArgumentException.class, () -> settings(true, null, "", 2));
		assertThrows(IllegalArgumentException.class,
				() -> settings(true, URI.create("http://user:secret@localhost"), "token", 2));
		assertThrows(IllegalArgumentException.class,
				() -> settings(true, URI.create("http://localhost/path"), "token", 2));
		assertThrows(IllegalArgumentException.class,
				() -> settings(true, URI.create("http://localhost"), "token\r\nheader", 2));
		assertThrows(IllegalArgumentException.class,
				() -> settings(true, URI.create("http://localhost:70000"), "token", 2));
		assertThrows(IllegalArgumentException.class,
				() -> settings(true, URI.create("http://localhost"), "token with spaces", 2));
		assertThrows(IllegalArgumentException.class,
				() -> settings(false, null, "", -1));
		assertThrows(IllegalArgumentException.class,
				() -> settings(false, null, "", 0));
		assertThrows(IllegalArgumentException.class, () -> new AgentDeliveryProperties(
				true, URI.create("http://localhost"), "token", Duration.ofSeconds(3),
				Duration.ofSeconds(10), Duration.ofSeconds(20), null, null, null, 3, 2, 1));
	}

	@Test
	void boundedBackoffAndConfigurationStringNeverExposeTheToken() {
		var properties = settings(true, URI.create("http://localhost"), "SECRET-TOKEN", 2);
		assertEquals(Duration.ofSeconds(2), properties.backoff(1));
		assertEquals(Duration.ofSeconds(4), properties.backoff(2));
		assertEquals(Duration.ofSeconds(10), properties.backoff(4));
		assertEquals(Duration.ofSeconds(10), properties.backoff(Integer.MAX_VALUE));
		assertFalse(properties.toString().contains("SECRET-TOKEN"));
	}

	@Test
	void schedulerDoesNotCreateAnUnboundedBacklogAndClosesItsWorkers() throws Exception {
		var worker = mock(AgentDeliveryWorker.class);
		var entered = new CountDownLatch(2);
		var release = new CountDownLatch(1);
		var finished = new CountDownLatch(2);
		var invocations = new AtomicInteger();
		when(worker.deliverOne()).thenAnswer(invocation -> {
			invocations.incrementAndGet();
			entered.countDown();
			try {
				assertTrue(release.await(5, TimeUnit.SECONDS));
				return true;
			} finally {
				finished.countDown();
			}
		});
		var scheduler = new AgentDeliveryConfiguration.DeliveryScheduler(worker,
				settings(true, URI.create("http://localhost"), "test", 2));
		try {
			scheduler.poll();
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			for (int i = 0; i < 20; i++) {
				scheduler.poll();
			}
			assertEquals(2, invocations.get());
		} finally {
			release.countDown();
			assertTrue(finished.await(5, TimeUnit.SECONDS));
			scheduler.close();
		}
		scheduler.poll();
		assertEquals(2, invocations.get());
	}

	@Test
	void envelopeRejectsMissingResumeAuthorityAndUnsupportedKinds() {
		UUID id = UUID.randomUUID();
		assertThrows(InvalidContractException.class, () -> new AgentTaskEnvelope(
				id, id, id, Kind.RESUME_REVIEW, "thread", id, 1, null, null, null, id));
		assertThrows(InvalidContractException.class, () -> new AgentTaskEnvelope(
				id, id, id, Kind.ANALYZE, "thread", id, 1, id, ReviewKind.KICK, id, id));
		assertThrows(InvalidContractException.class, () -> new AgentTaskEnvelope(
				id, id, id, Kind.ANALYZE, " ", id, 1, null, null, null, id));
		var mapper = JsonMapper.builder().build();
		assertThrows(tools.jackson.core.JacksonException.class, () -> mapper.readValue("""
				{"eventId":"%1$s","taskId":"%1$s","caseId":"%1$s","kind":"RECONCILE",
				 "threadId":"thread","snapshotId":"%1$s","expectedEvidenceVersion":1,"traceId":"%1$s"}
				""".formatted(id), AgentTaskEnvelope.class));
	}

	private AgentDeliveryProperties settings(boolean enabled, URI base, String token, int concurrency) {
		return new AgentDeliveryProperties(enabled, base, token, null, null, null, Duration.ofSeconds(1),
				Duration.ofSeconds(2), Duration.ofSeconds(10), 3, 10, concurrency);
	}
}
