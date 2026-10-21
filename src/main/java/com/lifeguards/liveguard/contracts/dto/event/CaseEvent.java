package com.lifeguards.liveguard.contracts.dto.event;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.CaseStatus;
import com.lifeguards.liveguard.contracts.enums.ConfirmationStatus;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;

public record CaseEvent(
		UUID caseId,
		UUID liveId,
		CaseStatus status,
		HandlingRoute handlingRoute,
		ConfirmationStatus confirmationStatus,
		FilterResult filterResult,
		int version) {
	public CaseEvent {
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(status, "status");
		DtoValidation.requireNonNull(handlingRoute, "handlingRoute");
		DtoValidation.requireNonNull(confirmationStatus, "confirmationStatus");
	}
}
