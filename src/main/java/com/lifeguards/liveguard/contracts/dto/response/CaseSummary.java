package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.CaseStatus;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.Verdict;

public record CaseSummary(
		UUID caseId,
		UUID channelId,
		String userAlias,
		FilterResult filterResult,
		CaseStatus status,
		Verdict verdict,
		Category category,
		int version,
		Instant createdAt) {
	public CaseSummary {
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(userAlias, "userAlias");
		DtoValidation.requireNonNull(filterResult, "filterResult");
		DtoValidation.requireNonNull(status, "status");
		DtoValidation.requireNonNull(createdAt, "createdAt");
	}
}
