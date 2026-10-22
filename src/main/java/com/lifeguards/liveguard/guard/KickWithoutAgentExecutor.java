package com.lifeguards.liveguard.guard;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lifeguards.liveguard.common.ApiException;
import com.lifeguards.liveguard.contracts.enums.ErrorCode;

/**
 * Agent가 실행하지 않는 승인된 강퇴(A-25)를 BE가 실행한다. AI 판정 없는 사건은 기다리는 Agent thread가 없고, 규칙·도배 사건도
 * Agent가 분석 중 실패하면 재개할 곳이 없다. 그래서 승인 뒤 {@code kick-handoff.deadline}이 지나도 Agent가 재검증하지 않은
 * 승인은 BE가 재검증해 실행 명령을 남기고(넘겨받기), Agent가 kick_user로 부르던 실행을 BE가 부른다. 실행 명령(PENDING)은 DB에
 * 남으므로 재시작해도 잃지 않는다. 업무 오류는 바로 FAILED, 그 밖의 일시 오류는 최초 포함 3회까지 다시 시도한다(TBD-07).
 * 넘겨받은 명령도 이 실행기가 실행하므로 둘은 한 스위치({@code kick-without-agent.enabled})로 함께 켜고 끈다. 끄면 BE는 강퇴를
 * 실행하지 않고 모두 Agent에 맡긴다.
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
	private final Duration handoff;
	private final Map<UUID, Integer> transientFailures = new ConcurrentHashMap<>();
	private final Map<UUID, Integer> handoffFailures = new ConcurrentHashMap<>();

	KickWithoutAgentExecutor(LiveModerationService moderation,
			@Value("${liveguard.guard.kick-handoff.deadline}") Duration handoff) {
		if (handoff.isNegative() || handoff.isZero()) {
			throw new IllegalArgumentException("liveguard.guard.kick-handoff.deadline must be positive");
		}
		this.moderation = moderation;
		this.handoff = handoff;
	}

	@Scheduled(fixedDelayString = "${liveguard.guard.kick-without-agent.poll-interval:1s}")
	void executePending() {
		handOffOverdue();
		List<UUID> pending;
		try {
			pending = moderation.pendingKicksWithoutAgent(handoff, BATCH);
		} catch (RuntimeException ex) {
			log.warn("Pending kicks not loaded: error={}", ex.getClass().getSimpleName());
			return;
		}
		pending.forEach(this::executeOne);
		// 다른 곳(방송 종료 등)에서 끝난 명령의 시도 횟수는 버린다
		transientFailures.keySet().retainAll(pending);
	}

	/** 마감이 지나도 Agent가 이어받지 않은 승인에 실행 명령을 남긴다. 실행은 같은 주기의 executeOne이 한다. */
	void handOffOverdue() {
		List<UUID> overdue;
		try {
			overdue = moderation.kickApprovalsAwaitingHandoff(handoff, BATCH);
		} catch (RuntimeException ex) {
			log.warn("Kick approvals awaiting handoff not loaded: error={}", ex.getClass().getSimpleName());
			return;
		}
		for (UUID decision : overdue) {
			try {
				moderation.handOffKick(decision);
				handoffFailures.remove(decision);
				log.info("Kick approval handed off to backend: decisionId={}", decision);
			} catch (ApiException ex) {
				// 방송 종료·권한 회수처럼 다시 해도 같은 결과인 실패다
				failHandoff(decision, ex.code());
			} catch (RuntimeException ex) {
				int attempts = handoffFailures.merge(decision, 1, Integer::sum);
				log.warn("Kick approval not handed off: decisionId={} attempt={} error={}", decision, attempts,
						ex.getClass().getSimpleName());
				if (attempts >= MAX_ATTEMPTS) {
					failHandoff(decision, ErrorCode.INTERNAL_ERROR);
				}
			}
		}
		handoffFailures.keySet().retainAll(overdue);
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

	private void failHandoff(UUID decision, ErrorCode code) {
		try {
			moderation.failKickHandoff(decision, code);
			handoffFailures.remove(decision);
		} catch (RuntimeException ex) {
			log.warn("Kick handoff failure not recorded: decisionId={} code={} error={}", decision, code,
					ex.getClass().getSimpleName());
		}
	}
}
