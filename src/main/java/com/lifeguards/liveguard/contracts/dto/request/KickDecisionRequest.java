package com.lifeguards.liveguard.contracts.dto.request;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.KickDecision;

public record KickDecisionRequest(
		UUID clientRequestId,
		int expectedReviewVersion,
		KickDecision decision,
		String reason) {

	private static final int MAX_REASON_LENGTH = 500;

	public KickDecisionRequest {
		DtoValidation.requireNonNull(clientRequestId, "clientRequestId");
		DtoValidation.requireNonNull(decision, "decision");
		reason = DtoValidation.requireBoundedText(reason, "reason", MAX_REASON_LENGTH);
	}
}
