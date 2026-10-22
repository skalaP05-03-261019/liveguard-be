package com.lifeguards.liveguard.guard;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;

/**
 * AI 판정 없는 사건의 승인된 강퇴(A-25)를 실행한다. 기다리는 Agent thread가 없으므로 Agent가 재개해 kick_user로 부르던
 * 실행을 BE가 부른다. 실행 명령(PENDING)은 결정과 같은 트랜잭션에 남으므로 재시작해도 잃지 않는다. 업무 오류는 바로 FAILED,
 * 그 밖의 일시 오류는 최초 포함 3회까지 다시 시도한다(TBD-07).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "liveguard.guard.kick-without-agent", name = "enabled", havingValue = "true",
		matchIfMissing = true)
class KickWithoutAgentExecutor {

	private static final Logger log = LoggerFactory.getLogger(KickWithoutAgentExecutor.class);
	private static final int BATCH = 20;
	static final int MAX_ATTEMPTS = 3;

	private final LiveModerationService moderation;
	private final Map<UUID, Integer> transientFailures = new ConcurrentHashMap<>();

	KickWithoutAgentExecutor(LiveModerationService moderation) {
		this.moderation = moderation;
	}

	@Scheduled(fixedDelayString = "${liveguard.guard.kick-without-agent.poll-interval:1s}")
	void executePending() {
		List<UUID> pending;
		try {
			pending = moderation.pendingKicksWithoutAgent(BATCH);
		} catch (RuntimeException ex) {
			log.warn("Pending kicks not loaded: error={}", ex.getClass().getSimpleName());
			return;
		}
		pending.forEach(this::executeOne);
		// 다른 곳(방송 종료 등)에서 끝난 명령의 시도 횟수는 버린다
		transientFailures.keySet().retainAll(pending);
	}

	void executeOne(UUID action) {
		try {
			moderation.executePendingKick(action);
			transientFailures.remove(action);
		} catch (ApiException ex) {
			// 기준·권한·대상 상태·방송 종료처럼 다시 해도 같은 결과인 실패다
			fail(action, ex.code(), transientFailures.merge(action, 1, Integer::sum));
		} catch (RuntimeException ex) {
			int attempts = transientFailures.merge(action, 1, Integer::sum);
			log.warn("Kick without Agent not executed: actionId={} attempt={} error={}", action, attempts,
					ex.getClass().getSimpleName());
			if (attempts >= MAX_ATTEMPTS) {
				fail(action, ErrorCode.INTERNAL_ERROR, attempts);
			}
		}
	}

	private void fail(UUID action, ErrorCode code, int attempts) {
		try {
			moderation.failPendingKick(action, code, attempts);
			// 기록한 뒤에만 지운다. 기록이 실패하면 다음 주기에 시도 횟수가 그대로라 다시 FAILED 기록을 시도한다
			transientFailures.remove(action);
		} catch (RuntimeException ex) {
			log.warn("Kick failure not recorded: actionId={} code={} error={}", action, code,
					ex.getClass().getSimpleName());
		}
	}
}
