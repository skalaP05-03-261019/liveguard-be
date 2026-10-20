package com.lifeguards.liveguard.contracts.dto.response;

import java.util.Objects;
import java.util.UUID;

public record DecisionAcceptedResponse(UUID decisionId, UUID taskId) {
	public DecisionAcceptedResponse {
		Objects.requireNonNull(decisionId, "decisionId");
		Objects.requireNonNull(taskId, "taskId");
	}
}
