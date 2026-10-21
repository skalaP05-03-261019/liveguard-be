package com.lifeguards.liveguard.platform.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.MatchingTag;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/**
 * 1차 필터가 PASS가 아니라고 판정한 메시지.
 *
 * @param liveId 메시지가 속한 방송(E-1). 모든 정책 범위의 기준이다. channelId는 고정 채널 식별자로만 쓴다
 * @param matchingTags 걸린 조건 전부 (InternalCase.matchingTags). 값은 계약의 {@link MatchingTag} 이름이다
 * @param filterVersion 이 판정에 쓴 정규화·사전 버전 조합 (예: {@code norm-v1+dict-v2}). 재현·감사용
 * @param sentAt 서버가 요청을 받아 주입 Clock으로 정한 UTC 시각(마이크로초). 클라이언트 시각이 아니며
 *        사건 생성 시각(B2의 created_at)과도 다르다. 재전송 응답과 같은 값이다
 * @param relatedMessageIds filterResult가 FLOOD일 때만, 같은 사용자·방송에서 10초 창 안에 있던 이전의
 *        같은 문장 메시지 id (오래된 순, 현재 메시지 제외). 그 외에는 빈 목록
 */
public record CandidateRequest(UUID channelId, UUID liveId, UUID userId, UUID messageId, FilterResult filterResult,
		List<String> matchingTags, String filterVersion, Instant sentAt, List<UUID> relatedMessageIds) {

	public CandidateRequest {
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(userId, "userId");
		DtoValidation.requireNonNull(messageId, "messageId");
		DtoValidation.requireNonNull(filterResult, "filterResult");
		DtoValidation.requireNonNull(sentAt, "sentAt");
		filterVersion = DtoValidation.requireText(filterVersion, "filterVersion");
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

}
