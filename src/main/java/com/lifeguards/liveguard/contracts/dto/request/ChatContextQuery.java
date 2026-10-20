package com.lifeguards.liveguard.contracts.dto.request;

import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record ChatContextQuery(UUID caseId, Integer before, Integer after) {
	public ChatContextQuery {
		Objects.requireNonNull(caseId, "caseId");
		before = before == null ? 10 : before;
		after = after == null ? 10 : after;
		if (before < 0 || before > 10 || after < 0 || after > 10 || before + after > 20) {
			throw new InvalidContractException("before and after must be between 0 and 10 (20 total max)");
		}
	}
}
