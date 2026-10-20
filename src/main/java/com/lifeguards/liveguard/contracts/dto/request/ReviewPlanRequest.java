package com.lifeguards.liveguard.contracts.dto.request;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record ReviewPlanRequest(int expectedDecisionVersion) {
	public ReviewPlanRequest {
		DtoValidation.requireNonNegative(expectedDecisionVersion, "expectedDecisionVersion");
	}
}
