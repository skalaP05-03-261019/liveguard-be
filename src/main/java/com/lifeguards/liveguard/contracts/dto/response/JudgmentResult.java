package com.lifeguards.liveguard.contracts.dto.response;

import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.enums.CaseStatus;

public record JudgmentResult(CaseStatus caseStatus, int violationCount, UUID kickReviewId) {
	public JudgmentResult {
		Objects.requireNonNull(caseStatus, "caseStatus");
	}
}
