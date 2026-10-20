package com.lifeguards.liveguard.contracts.dto.event;

import java.util.Objects;
import java.util.UUID;

public record TaskFailedEvent(UUID caseId, String stage, String errorCode, String traceUrl) {
	public TaskFailedEvent {
		Objects.requireNonNull(caseId, "caseId");
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(errorCode, "errorCode");
	}
}
