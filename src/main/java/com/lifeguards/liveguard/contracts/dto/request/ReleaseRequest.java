package com.lifeguards.liveguard.contracts.dto.request;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record ReleaseRequest(int expectedVersion, String reason) {
	private static final int MAX_REASON_LENGTH = 500;

	public ReleaseRequest {
		reason = DtoValidation.requireBoundedText(reason, "reason", MAX_REASON_LENGTH);
	}
}
