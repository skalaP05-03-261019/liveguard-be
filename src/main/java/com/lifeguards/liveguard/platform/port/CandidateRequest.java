package com.lifeguards.liveguard.platform.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.MatchingTag;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/**
 * 1차 필터가 PASS가 아니라고 판정한 메시지.
 *
 * @param liveId 메시지가 속한 방송(E-1). 모든 정책 범위의 기준이다. channelId는 고정 채널 식별자로만 쓴다
 * @param handlingRoute 1차 필터가 정한 처리 경로 하나(TBD-02). PASS는 후보가 아니므로 오지 않는다.
 *        filterResult와 짝이 맞아야 한다: FLOOD_REVIEW는 FLOOD, 그 외 경로는 HOLD
 * @param ruleId 규칙으로 확정한 근거 규칙 식별자(예: {@code RULE_LINK_BLOCKED}). RULE_CONFIRMED일 때만 있고 그 외에는 null.
 *        BE2가 검증해 사건·finding을 확정할 때 쓴다. 후보 전달만으로 확정되지는 않는다
 * @param matchingTags 걸린 조건 전부 (InternalCase.matchingTags). 값은 계약의 {@link MatchingTag} 이름이다
 * @param filterVersion 이 판정에 쓴 정규화·사전·규칙 버전 조합 (예: {@code norm-v1+dict-v2+rules-v2}). 재현·감사용
 * @param policyVersion 이 후보에 적용한 정책 버전(LIVEGUARD_POLICY_VERSION, 기본 v3.1). filterVersion과 다르다
 * @param sentAt 서버가 요청을 받아 주입 Clock으로 정한 UTC 시각(마이크로초). 클라이언트 시각이 아니며
 *        사건 생성 시각(B2의 created_at)과도 다르다. 재전송 응답과 같은 값이다
 * @param relatedMessageIds filterResult가 FLOOD일 때만, 같은 사용자·방송에서 10초 창 안에 있던 이전의
 *        같은 문장 메시지 id (오래된 순, 현재 메시지 제외). 그 외에는 빈 목록
 */
public record CandidateRequest(UUID channelId, UUID liveId, UUID userId, UUID messageId, FilterResult filterResult,
		HandlingRoute handlingRoute, String ruleId, List<String> matchingTags, String filterVersion,
		String policyVersion, Instant sentAt, List<UUID> relatedMessageIds) {

	public CandidateRequest {
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(userId, "userId");
		DtoValidation.requireNonNull(messageId, "messageId");
		DtoValidation.requireNonNull(filterResult, "filterResult");
		DtoValidation.requireNonNull(handlingRoute, "handlingRoute");
		DtoValidation.requireNonNull(sentAt, "sentAt");
		if (!consistent(filterResult, handlingRoute)) {
			throw new InvalidContractException("filterResult does not match handlingRoute");
		}
		if ((handlingRoute == HandlingRoute.RULE_CONFIRMED) != (ruleId != null)) {
			throw new InvalidContractException("ruleId must be set exactly when handlingRoute is RULE_CONFIRMED");
		}
		ruleId = ruleId == null ? null : DtoValidation.requireText(ruleId, "ruleId");
		filterVersion = DtoValidation.requireText(filterVersion, "filterVersion");
		policyVersion = DtoValidation.requireText(policyVersion, "policyVersion");
		matchingTags = DtoValidation.immutableList(matchingTags, "matchingTags");
		for (String tag : matchingTags) {
			try {
				MatchingTag.valueOf(tag);
			} catch (IllegalArgumentException ex) {
				throw new InvalidContractException("matchingTags contains an unknown tag");
			}
		}
		relatedMessageIds = DtoValidation.immutableList(relatedMessageIds, "relatedMessageIds");
	}

	private static boolean consistent(FilterResult filterResult, HandlingRoute handlingRoute) {
		return switch (handlingRoute) {
			case PASS -> filterResult == FilterResult.PASS;
			case FLOOD_REVIEW -> filterResult == FilterResult.FLOOD;
			case RULE_CONFIRMED, CONTEXT_REVIEW, PII_INVESTIGATION -> filterResult == FilterResult.HOLD;
		};
	}

}
