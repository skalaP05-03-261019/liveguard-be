package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.CaseStatus;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.Verdict;

/** CaseSummary의 모든 필드에 상세 필드를 더한 평탄한 구조 (openapi.yaml의 allOf). */
public record CaseDetail(
		UUID caseId,
		UUID channelId,
		String userAlias,
		FilterResult filterResult,
		CaseStatus status,
		Verdict verdict,
		Category category,
		int version,
		Instant createdAt,
		List<Message> messages,
		String reason,
		List<UUID> evidenceMessageIds,
		List<PolicySnapshot> policySnapshots,
		int violationCount,
		KickReview kickReview,
		String traceUrl) {
	public CaseDetail {
		Objects.requireNonNull(caseId, "caseId");
		Objects.requireNonNull(channelId, "channelId");
		Objects.requireNonNull(userAlias, "userAlias");
		Objects.requireNonNull(filterResult, "filterResult");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(createdAt, "createdAt");
		messages = DtoValidation.immutableList(messages, "messages");
		evidenceMessageIds = DtoValidation.immutableList(evidenceMessageIds, "evidenceMessageIds");
		policySnapshots = DtoValidation.immutableList(policySnapshots, "policySnapshots");
	}
}
