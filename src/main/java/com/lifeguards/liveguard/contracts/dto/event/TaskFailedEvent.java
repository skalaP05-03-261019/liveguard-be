package com.lifeguards.liveguard.contracts.dto.event;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record TaskFailedEvent(UUID caseId, String stage, String errorCode, String traceUrl) {
	public TaskFailedEvent {
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(stage, "stage");
		DtoValidation.requireNonNull(errorCode, "errorCode");
	}
}
