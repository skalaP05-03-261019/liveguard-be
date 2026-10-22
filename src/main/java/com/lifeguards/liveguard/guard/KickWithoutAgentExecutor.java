package com.lifeguards.liveguard.guard;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.lifeguards.liveguard.common.ApiException;

/**
 * AI 판정 없는 사건의 강퇴 승인(A-25)을 결정이 커밋된 뒤 실행한다. 기다리는 Agent thread가 없으므로 Agent가 재개해
 * kick_user로 부르던 재검증·실행을 BE가 부른다. 실행은 결정과 다른 트랜잭션이라 실패해도 결정은 남고, 실패는 강퇴 실행
 * 기록(FAILED)과 ACTION_FAILED로 알린다.
 */
@Component
class KickWithoutAgentExecutor {

	private static final Logger log = LoggerFactory.getLogger(KickWithoutAgentExecutor.class);

	private final LiveModerationService moderation;
	private final TransactionTemplate transactions;

	KickWithoutAgentExecutor(LiveModerationService moderation, PlatformTransactionManager transactionManager) {
		this.moderation = moderation;
		this.transactions = new TransactionTemplate(transactionManager);
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@TransactionalEventListener
	void execute(LiveModerationService.KickApprovedWithoutAgent approved) {
		String failure;
		try {
			failure = transactions.execute(status -> {
				var validated = moderation.revalidate(approved.reviewId(), approved.decisionId());
				if (!Boolean.TRUE.equals(validated.get("valid"))) {
					// 기준이 더는 유효하지 않다. 재검증이 검토를 INVALIDATED로 바꾼다
					return "REVIEW_NOT_APPROVED";
				}
				@SuppressWarnings("unchecked")
				var command = (Map<String, Object>) validated.get("actionCommand");
				moderation.execute(UUID.fromString(command.get("actionId").toString()), command);
				return null;
			});
		} catch (ApiException ex) {
			failure = ex.code().name();
		} catch (RuntimeException ex) {
			log.warn("Kick without Agent failed: reviewId={} error={}", approved.reviewId(), ex.getClass().getSimpleName());
			failure = "INTERNAL_ERROR";
		}
		if (failure != null) {
			String code = failure;
			try {
				transactions.executeWithoutResult(
						status -> moderation.kickFailedWithoutAgent(approved.reviewId(), approved.decisionId(), code));
			} catch (RuntimeException ex) {
				// 결정은 이미 커밋됐다. 결정 응답을 실패로 바꾸지 않는다
				log.warn("Kick failure not recorded: reviewId={} code={} error={}", approved.reviewId(), code,
						ex.getClass().getSimpleName());
			}
		}
	}
}
