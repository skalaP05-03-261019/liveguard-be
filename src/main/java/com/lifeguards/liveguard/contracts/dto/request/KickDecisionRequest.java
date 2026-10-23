package com.lifeguards.liveguard.contracts.dto.request;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.KickDecision;

/** 강퇴 검토 결정. reason은 선택이다(A-26). */
public record KickDecisionRequest(
		UUID clientRequestId,
		int expectedReviewVersion,
		KickDecision decision,
		String reason) {

	private static final int MAX_REASON_LENGTH = 500;

	public KickDecisionRequest {
		DtoValidation.requireNonNull(clientRequestId, "clientRequestId");
		DtoValidation.requireNonNull(decision, "decision");
		reason = DtoValidation.optionalBoundedText(reason, "reason", MAX_REASON_LENGTH);
	}
}
