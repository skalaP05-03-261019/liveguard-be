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
		// POST 예산은 전달이 불확실한 행에만 적용한다(Agent가 받았을 수 있는 작업을 거듭 보내지 않는다). 이전 시도가 모두
		// 확실히 닿지 않았으면 다시 보내도 두 번 실행될 수 없다
		var result = gateway.deliver(delivery.envelope(), delivery.uncertain(),
				!delivery.uncertain() || delivery.attemptCount() <= properties.maxAttempts());
		var now = clock.instant();
		// Agent에 닿지 않은 실패(AGENT_UNREACHABLE)는 끝내지 않고 최대 간격으로 계속 시도한다. Agent 재시작·배포가 예산보다
		// 길어도 분석이 멈추지 않게 한다. 방송 종료·대체 처리는 RETRY 행을 정리한다
		boolean unreachable = "AGENT_UNREACHABLE".equals(result.code()) && !result.uncertain() && !delivery.uncertain();
		boolean retry = result.retryable() && (unreachable || delivery.attemptCount() < properties.maxAttempts());
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
