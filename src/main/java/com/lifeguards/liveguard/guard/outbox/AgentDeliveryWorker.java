package com.lifeguards.liveguard.guard.outbox;

import java.time.Clock;
import java.util.UUID;

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
		return deliver(null);
	}

	/** 전송 문제로 멈춘 현재 작업을 다시 보낼 수 있게 한다(Agent 재기동 후 복구). 개수만 남긴다. */
	public int revive(java.time.Duration staleAfter) {
		var now = clock.instant();
		int revived = repository.revive(now, now.minus(staleAfter));
		if (revived > 0) {
			log.info("Agent delivery revived stalled tasks: count={}", revived);
		}
		return revived;
	}

	/** True means an outcome was persisted, or no eligible row exists for this notification. */
	public boolean deliver(UUID id) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Agent delivery must run outside a business transaction");
		}
		var nowForClaim = clock.instant();
		var claimed = id == null
				? repository.claim(nowForClaim, nowForClaim.plus(properties.leaseDuration()))
				: repository.claim(id, nowForClaim, nowForClaim.plus(properties.leaseDuration()));
		if (claimed.isEmpty()) {
			return id != null;
		}
		var delivery = claimed.get();
		var result = gateway.deliver(delivery.envelope(), delivery.uncertain(),
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
		if (!saved) {
			throw new IllegalStateException("Agent delivery outcome was not persisted");
		}
		return true;
	}
}
