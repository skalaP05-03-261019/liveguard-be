package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.dto.HarassmentProfile;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeguards.liveguard.contracts.enums.CaseStatus;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.ConfirmationStatus;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.Verdict;

public record CaseSummary(
		UUID caseId,
		UUID liveId,
		UUID channelId,
		String userAlias,
		FilterResult filterResult,
		HandlingRoute handlingRoute,
		CaseStatus status,
		ConfirmationStatus confirmationStatus,
		Verdict verdict,
		Category category,
		int version,
		Instant createdAt,
		@JsonInclude(JsonInclude.Include.NON_NULL) String policyVersion,
		@JsonInclude(JsonInclude.Include.NON_NULL) Integer evidenceSchemaVersion,
		@JsonInclude(JsonInclude.Include.NON_NULL) HarassmentProfile harassmentProfile) {
	public CaseSummary(UUID caseId, UUID liveId, UUID channelId, String userAlias, FilterResult filterResult,
			HandlingRoute handlingRoute, CaseStatus status, ConfirmationStatus confirmationStatus, Verdict verdict,
			Category category, int version, Instant createdAt) {
		this(caseId, liveId, channelId, userAlias, filterResult, handlingRoute, status, confirmationStatus, verdict,
				category, version, createdAt, null, null, null);
	}

	public CaseSummary {
		HarassmentProfile.validateEvidence(evidenceSchemaVersion, policyVersion, harassmentProfile);
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(userAlias, "userAlias");
		DtoValidation.requireNonNull(filterResult, "filterResult");
		DtoValidation.requireNonNull(handlingRoute, "handlingRoute");
		DtoValidation.requireNonNull(status, "status");
		DtoValidation.requireNonNull(confirmationStatus, "confirmationStatus");
		DtoValidation.requireNonNull(createdAt, "createdAt");
	}
}
