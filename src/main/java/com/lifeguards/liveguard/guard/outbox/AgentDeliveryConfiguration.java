package com.lifeguards.liveguard.guard.outbox;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentDeliveryProperties.class)
public class AgentDeliveryConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "liveguard.agent.delivery", name = "enabled", havingValue = "true")
	@EnableScheduling
	static class EnabledDelivery {
		@Bean
		AgentHttpGateway agentHttpGateway(AgentDeliveryProperties properties, JsonMapper mapper) {
			return new AgentHttpGateway(properties, mapper);
		}

		@Bean
		AgentDeliveryWorker agentDeliveryWorker(AgentOutboxRepository repository, AgentHttpGateway gateway,
				AgentDeliveryProperties properties, Clock clock) {
			return new AgentDeliveryWorker(repository, gateway, properties, clock);
		}

		@Bean(destroyMethod = "close")
		DeliveryScheduler deliveryScheduler(AgentDeliveryWorker worker, AgentDeliveryProperties properties) {
			return new DeliveryScheduler(worker, properties);
		}
	}

	static class DeliveryScheduler implements AutoCloseable {
		private static final Logger log = LoggerFactory.getLogger(DeliveryScheduler.class);
		private final AgentDeliveryWorker worker;
		private final AgentDeliveryProperties properties;
		private final ExecutorService executor;
		private final Semaphore slots;
		private volatile boolean closed;

		DeliveryScheduler(AgentDeliveryWorker worker, AgentDeliveryProperties properties) {
			this.worker = worker;
			this.properties = properties;
			this.slots = new Semaphore(properties.concurrency());
			this.executor = Executors.newFixedThreadPool(properties.concurrency(),
					Thread.ofPlatform().name("agent-delivery-", 0).factory());
		}

		@Scheduled(fixedDelayString = "${liveguard.agent.delivery.poll-interval:1s}")
		public synchronized void poll() {
			if (closed) {
				return;
			}
			for (int i = 0; i < properties.batchSize() && slots.tryAcquire(); i++) {
				executor.execute(() -> {
					try {
						worker.deliverOne();
					} catch (RuntimeException ex) {
						// Preserve the lease for recovery; never log exception text or payloads.
						log.error("Agent delivery worker failed: type={}", ex.getClass().getName());
					} finally {
						slots.release();
					}
				});
			}
		}

		@Override
		public void close() {
			synchronized (this) {
				closed = true;
				executor.shutdown();
			}
			try {
				if (!executor.awaitTermination(properties.leaseDuration().toMillis(), TimeUnit.MILLISECONDS)) {
					executor.shutdownNow();
				}
			} catch (InterruptedException ex) {
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}
	}
}
