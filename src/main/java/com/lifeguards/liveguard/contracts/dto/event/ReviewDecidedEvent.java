package com.lifeguards.liveguard.contracts.dto.event;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ReviewKind;

/** REVIEW_DECIDED payload. 결정 저장 알림이며 강퇴 완료를 뜻하지 않는다. value는 Decision.value와 같은 규칙이다. */
public record ReviewDecidedEvent(UUID reviewId, ReviewKind reviewKind, UUID decisionId, String value) {
	public ReviewDecidedEvent {
		DtoValidation.requireNonNull(reviewId, "reviewId");
		value = DtoValidation.requireDecisionValue(reviewKind, value);
		DtoValidation.requireNonNull(decisionId, "decisionId");
	}
}
