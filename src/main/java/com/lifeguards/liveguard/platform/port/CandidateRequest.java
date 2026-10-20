package com.lifeguards.liveguard.platform.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.FilterResult;

/**
 * 1차 필터가 PASS가 아니라고 판정한 메시지.
 *
 * @param matchingTags 걸린 조건 전부 (InternalCase.matchingTags)
 * @param relatedMessageIds filterResult가 FLOOD일 때만, 같은 사용자·채널에서 10초 창 안에 있던 이전의
 *        같은 문장 메시지 id (오래된 순). 그 외에는 빈 목록
 */
public record CandidateRequest(UUID channelId, UUID userId, UUID messageId, FilterResult filterResult,
		List<String> matchingTags, Instant sentAt, List<UUID> relatedMessageIds) {

	public CandidateRequest {
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(userId, "userId");
		DtoValidation.requireNonNull(messageId, "messageId");
		DtoValidation.requireNonNull(filterResult, "filterResult");
		DtoValidation.requireNonNull(sentAt, "sentAt");
		matchingTags = DtoValidation.immutableList(matchingTags, "matchingTags");
		relatedMessageIds = DtoValidation.immutableList(relatedMessageIds, "relatedMessageIds");
	}

}
