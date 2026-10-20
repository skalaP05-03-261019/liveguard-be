package com.lifeguards.liveguard.contracts.dto.request;

import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record HistoryQuery(UUID caseId, Integer days) {
	public HistoryQuery {
		Objects.requireNonNull(caseId, "caseId");
		days = days == null ? 30 : days;
		if (days < 1 || days > 30) {
			throw new InvalidContractException("days must be between 1 and 30");
		}
	}
}
