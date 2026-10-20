package com.lifeguards.liveguard.internal;

import java.util.Objects;
import java.util.UUID;

public record ReconcileReviewRequest(UUID caseId, UUID decisionId) {
	public ReconcileReviewRequest {
		Objects.requireNonNull(caseId, "caseId");
		Objects.requireNonNull(decisionId, "decisionId");
	}
}
