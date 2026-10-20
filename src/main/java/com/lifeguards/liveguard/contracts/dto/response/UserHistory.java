package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/** TIMEOUT history: excludes the current case and retains cases consumed by kick reviews. */
public record UserHistory(int violationCount, int windowHours, List<RecentCase> recentCases) {
	public UserHistory {
		if (windowHours <= 0) {
			throw new InvalidContractException("windowHours must be positive");
		}
		if (violationCount < 0) {
			throw new InvalidContractException("violationCount must not be negative");
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
