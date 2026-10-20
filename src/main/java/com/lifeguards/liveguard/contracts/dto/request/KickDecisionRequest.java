package com.lifeguards.liveguard.contracts.dto.request;

import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.KickDecision;

public record KickDecisionRequest(
		KickDecision decision,
		String reason,
		int expectedVersion,
		UUID clientRequestId) {
	private static final int MAX_REASON_LENGTH = 500;

	public KickDecisionRequest {
		Objects.requireNonNull(decision, "decision");
		reason = DtoValidation.requireBoundedText(reason, "reason", MAX_REASON_LENGTH);
		Objects.requireNonNull(clientRequestId, "clientRequestId");
	}
}
