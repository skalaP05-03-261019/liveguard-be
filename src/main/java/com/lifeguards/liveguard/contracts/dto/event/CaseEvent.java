package com.lifeguards.liveguard.contracts.dto.event;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.CaseStatus;
import com.lifeguards.liveguard.contracts.enums.FilterResult;

public record CaseEvent(UUID caseId, CaseStatus status, FilterResult filterResult, int version) {
	public CaseEvent {
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(status, "status");
	}
}
