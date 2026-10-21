package com.lifeguards.liveguard.contracts.dto.event;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Verdict;

/** 담당 운영자에게 보내는 CONTEXT_REVIEW_REQUIRED payload. AI 정상 제안이어도 결정이 필요하다. */
public record ContextReviewRequiredEvent(UUID reviewId, UUID caseId, Verdict aiVerdict) {
	public ContextReviewRequiredEvent {
		DtoValidation.requireNonNull(reviewId, "reviewId");
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(aiVerdict, "aiVerdict");
	}
}
