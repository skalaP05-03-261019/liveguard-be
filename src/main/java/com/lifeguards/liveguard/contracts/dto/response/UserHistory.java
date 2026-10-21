package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/** Distinct-case count and case/category rows; excludes current case, not kick-consumed history. */
public record UserHistory(int violationCount, List<RecentCase> recentCases) {
	public UserHistory {
		if (violationCount < 0) {
			throw new InvalidContractException("violationCount must be non-negative");
		}
		recentCases = DtoValidation.immutableList(recentCases, "recentCases");
	}

	public record RecentCase(UUID caseId, Category category, Instant confirmedAt) {
		public RecentCase {
			DtoValidation.requireNonNull(caseId, "caseId");
			DtoValidation.requireNonNull(category, "category");
			DtoValidation.requireNonNull(confirmedAt, "confirmedAt");
		}
	}
}
