package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/** TIMEOUT history excludes the current case; kick consumption does not delete it. */
public record UserHistory(int violationCount, int windowHours, List<RecentCase> recentCases) {
	public UserHistory {
		if (windowHours <= 0 || violationCount < 0) {
			throw new InvalidContractException("windowHours must be positive and violationCount non-negative");
		}
		recentCases = DtoValidation.immutableList(recentCases, "recentCases");
	}

	public record RecentCase(UUID caseId, Category category, Instant judgedAt) {
		public RecentCase {
			DtoValidation.requireNonNull(caseId, "caseId");
			DtoValidation.requireNonNull(category, "category");
			DtoValidation.requireNonNull(judgedAt, "judgedAt");
		}
	}
}
