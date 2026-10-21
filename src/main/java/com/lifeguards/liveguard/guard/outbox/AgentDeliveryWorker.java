package com.lifeguards.liveguard.guard.outbox;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class AgentDeliveryWorker {

	private static final Logger log = LoggerFactory.getLogger(AgentDeliveryWorker.class);

	private final AgentOutboxRepository repository;
	private final AgentHttpGateway gateway;
	private final AgentDeliveryProperties properties;
	private final Clock clock;

	public AgentDeliveryWorker(AgentOutboxRepository repository, AgentHttpGateway gateway,
			AgentDeliveryProperties properties, Clock clock) {
		this.repository = repository;
		this.gateway = gateway;
		this.properties = properties;
		this.clock = clock;
	}

	public boolean deliverOne() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Agent delivery must run outside a business transaction");
		}
		var claimed = repository.claim(clock.instant(), clock.instant().plus(properties.leaseDuration()));
		if (claimed.isEmpty()) {
			return false;
		}
		var delivery = claimed.get();
		var result = gateway.deliver(delivery.envelope(), delivery.attemptCount() > 1,
				delivery.attemptCount() <= properties.maxAttempts());
		var now = clock.instant();
		boolean retry = result.retryable() && delivery.attemptCount() < properties.maxAttempts();
		boolean uncertain = result.uncertain() || delivery.uncertain();
		String status = result.accepted() ? "ACCEPTED" : retry ? "RETRY"
				: uncertain ? "UNKNOWN" : "FAILED";
		var next = retry ? now.plus(properties.backoff(delivery.attemptCount())) : now;
		boolean saved = repository.finish(delivery, status, result.code(), next, now, uncertain);
		if (!saved) {
			log.warn("Agent delivery lease lost: outboxId={}", delivery.id());
		} else if (!result.accepted()) {
			log.warn("Agent delivery not accepted: outboxId={} status={} code={} attempt={}",
					delivery.id(), status, result.code(), delivery.attemptCount());
		}
		return true;
	}
}
