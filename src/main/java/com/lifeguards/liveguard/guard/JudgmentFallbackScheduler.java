package com.lifeguards.liveguard.guard;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agent 판정이 마감 안에 오지 않거나 분석이 거절된 문맥·개인정보 사건에 AI 판정 없는 문맥 검토를 연다(A-25). 메시지는
 * HELD로 두고(자동 공개 없음) 운영자가 결정한다. 사건마다 따로 트랜잭션을 연다.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "liveguard.guard.judgment-fallback", name = "enabled", havingValue = "true",
		matchIfMissing = true)
class JudgmentFallbackScheduler {

	private static final Logger log = LoggerFactory.getLogger(JudgmentFallbackScheduler.class);
	private static final int BATCH = 50;

	private final LiveModerationService moderation;
	private final Duration deadline;

	JudgmentFallbackScheduler(LiveModerationService moderation,
			@Value("${liveguard.guard.judgment-fallback.deadline}") Duration deadline) {
		if (deadline.isNegative() || deadline.isZero()) {
			throw new IllegalArgumentException("liveguard.guard.judgment-fallback.deadline must be positive");
		}
		this.moderation = moderation;
		this.deadline = deadline;
	}

	@Scheduled(fixedDelayString = "${liveguard.guard.judgment-fallback.poll-interval:10s}")
	void openOverdueReviews() {
		List<UUID> due;
		try {
			due = moderation.casesAwaitingJudgment(deadline, BATCH);
		} catch (RuntimeException ex) {
			log.warn("Cases awaiting judgment not loaded: error={}", ex.getClass().getSimpleName());
			return;
		}
		for (UUID caseId : due) {
			try {
				if (moderation.openReviewWithoutJudgment(caseId, deadline)) {
					log.info("Context review opened without AI judgment: caseId={}", caseId);
				}
			} catch (RuntimeException ex) {
				log.warn("Context review without AI judgment not opened: caseId={} error={}", caseId,
						ex.getClass().getSimpleName());
			}
		}
	}
}
