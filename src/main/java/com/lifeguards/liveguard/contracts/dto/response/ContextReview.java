package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.dto.HarassmentProfile;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeguards.liveguard.contracts.enums.ReviewKind;
import com.lifeguards.liveguard.contracts.enums.ReviewStatus;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/** 문맥 확정 검토. AI 정상 제안이어도 운영자 결정 전까지 PENDING이다. threadId는 브라우저에 보내지 않는다(A-6). */
public record ContextReview(
		UUID reviewId,
		ReviewKind kind,
		UUID liveId,
		UUID caseId,
		Verdict aiVerdict,
		int evidenceVersion,
		ReviewStatus status,
		int version,
		Instant createdAt,
		@JsonInclude(JsonInclude.Include.NON_NULL) String policyVersion,
		@JsonInclude(JsonInclude.Include.NON_NULL) Integer evidenceSchemaVersion,
		@JsonInclude(JsonInclude.Include.NON_NULL) HarassmentProfile harassmentProfile) implements Review {
	public ContextReview(UUID reviewId, ReviewKind kind, UUID liveId, UUID caseId, Verdict aiVerdict,
			int evidenceVersion, ReviewStatus status, int version, Instant createdAt) {
		this(reviewId, kind, liveId, caseId, aiVerdict, evidenceVersion, status, version, createdAt, null, null, null);
	}

	public ContextReview {
		HarassmentProfile.validateEvidence(evidenceSchemaVersion, policyVersion, harassmentProfile);
		DtoValidation.requireNonNull(reviewId, "reviewId");
		if (kind != ReviewKind.CONTEXT) {
			throw new InvalidContractException("ContextReview kind must be CONTEXT");
		}
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(aiVerdict, "aiVerdict");
		if (evidenceVersion < 1) {
			throw new InvalidContractException("evidenceVersion must be positive");
		}
		DtoValidation.requireNonNull(status, "status");
		DtoValidation.requireNonNull(createdAt, "createdAt");
	}
}
