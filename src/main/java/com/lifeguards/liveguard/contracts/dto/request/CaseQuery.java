package com.lifeguards.liveguard.contracts.dto.request;

import java.util.Objects;
import java.util.UUID;

public record CaseQuery(UUID caseId) {
	public CaseQuery {
		Objects.requireNonNull(caseId, "caseId");
	}
}
