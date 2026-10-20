package com.lifeguards.liveguard.contracts.dto.event;

import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.enums.CaseStatus;
import com.lifeguards.liveguard.contracts.enums.FilterResult;

public record CaseEvent(UUID caseId, CaseStatus status, FilterResult filterResult, int version) {
	public CaseEvent {
		Objects.requireNonNull(caseId, "caseId");
		Objects.requireNonNull(status, "status");
	}
}
