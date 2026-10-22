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
		// Agent에 확실히 닿지 않은 시도는 전달 예산을 쓰지 않는다. Agent 재시작·배포가 예산보다 길어도 끝나지 않고,
		// 돌아온 뒤에도 예산이 그대로 남는다. 간격은 연결 차단기 간격을 따른다(최대 maxBackoff)
		int spent = delivery.attemptCount() - (result.notSent() ? 1 : 0);
		boolean retry = result.retryable() && spent < properties.maxAttempts();
		boolean uncertain = result.uncertain() || delivery.uncertain();
		String status = result.accepted() ? "ACCEPTED" : retry ? "RETRY"
				: uncertain ? "UNKNOWN" : "FAILED";
		var next = !retry ? now
				: now.plus(result.notSent() ? gateway.unreachableBackoff() : properties.backoff(delivery.attemptCount()));
		boolean saved = repository.finish(delivery, status, result.code(), next, now, uncertain, result.notSent());
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
