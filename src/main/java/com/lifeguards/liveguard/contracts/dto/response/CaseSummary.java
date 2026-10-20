package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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
		Objects.requireNonNull(caseId, "caseId");
		Objects.requireNonNull(channelId, "channelId");
		Objects.requireNonNull(userAlias, "userAlias");
		Objects.requireNonNull(filterResult, "filterResult");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(createdAt, "createdAt");
	}
}
