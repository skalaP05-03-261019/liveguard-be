package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record UserHistory(int violationCount, int windowHours, List<RecentCase> recentCases) {
	private static final int WINDOW_HOURS = 24;

	public UserHistory {
		if (windowHours != WINDOW_HOURS) {
			throw new InvalidContractException("windowHours must be " + WINDOW_HOURS);
		}
		recentCases = DtoValidation.immutableList(recentCases, "recentCases");
	}

	public record RecentCase(UUID caseId, Category category, Instant judgedAt) {
		public RecentCase {
			Objects.requireNonNull(caseId, "caseId");
			Objects.requireNonNull(category, "category");
			Objects.requireNonNull(judgedAt, "judgedAt");
		}
	}
}
