package com.lifeguards.liveguard.contracts.dto.event;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/** KICK_REVIEW_REQUIRED payload. count는 다섯 유형 통합, 현재 포함 사건 수다. */
public record KickReviewRequiredEvent(UUID reviewId, int count) {
	public KickReviewRequiredEvent {
		DtoValidation.requireNonNull(reviewId, "reviewId");
		if (count < 3) {
			throw new InvalidContractException("count must be at least 3");
		}
	}
}
