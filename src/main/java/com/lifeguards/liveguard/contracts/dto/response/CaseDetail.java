package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.dto.HarassmentProfile;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeguards.liveguard.contracts.dto.request.JudgmentRequest;
import com.lifeguards.liveguard.contracts.enums.CaseStatus;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.ConfirmationStatus;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.Verdict;

/** CaseSummary의 모든 필드에 상세 필드를 더한 평탄한 구조 (openapi.yaml의 allOf). */
public record CaseDetail(
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
		List<Message> messages,
		String reason,
		List<UUID> evidenceMessageIds,
		List<UUID> targetMessageIds,
		List<JudgmentRequest.MessageFinding> findings,
		List<PolicySnapshot> policySnapshots,
		List<ConfirmedFinding> confirmedFindings,
		int violationCount,
		KickReviewState kickReview,
		String traceUrl,
		@JsonInclude(JsonInclude.Include.NON_NULL) String policyVersion,
		@JsonInclude(JsonInclude.Include.NON_NULL) Integer evidenceSchemaVersion,
		@JsonInclude(JsonInclude.Include.NON_NULL) HarassmentProfile harassmentProfile) {
	public CaseDetail(UUID caseId, UUID liveId, UUID channelId, String userAlias, FilterResult filterResult,
			HandlingRoute handlingRoute, CaseStatus status, ConfirmationStatus confirmationStatus, Verdict verdict,
			Category category, int version, Instant createdAt, List<Message> messages, String reason,
			List<UUID> evidenceMessageIds, List<UUID> targetMessageIds, List<JudgmentRequest.MessageFinding> findings,
			List<PolicySnapshot> policySnapshots, List<ConfirmedFinding> confirmedFindings, int violationCount,
			KickReviewState kickReview, String traceUrl) {
		this(caseId, liveId, channelId, userAlias, filterResult, handlingRoute, status, confirmationStatus, verdict,
				category, version, createdAt, messages, reason, evidenceMessageIds, targetMessageIds, findings,
				policySnapshots, confirmedFindings, violationCount, kickReview, traceUrl, null, null, null);
	}

	public CaseDetail {
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
		messages = DtoValidation.immutableList(messages, "messages");
		evidenceMessageIds = DtoValidation.immutableList(evidenceMessageIds, "evidenceMessageIds");
		targetMessageIds = DtoValidation.immutableList(targetMessageIds, "targetMessageIds");
		findings = DtoValidation.immutableList(findings, "findings");
		policySnapshots = DtoValidation.immutableList(policySnapshots, "policySnapshots");
		confirmedFindings = DtoValidation.immutableList(confirmedFindings, "confirmedFindings");
		DtoValidation.requireNonNull(kickReview, "kickReview");
	}
}
