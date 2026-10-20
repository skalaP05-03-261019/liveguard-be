package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.KickReviewStatus;

public record KickReview(
		UUID reviewId,
		UUID channelId,
		String userAlias,
		KickReviewStatus status,
		String summary,
		List<UUID> caseIds,
		String decisionReason,
		Instant kickExpiresAt,
		int version,
		Instant createdAt) {
	public KickReview {
		DtoValidation.requireNonNull(reviewId, "reviewId");
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(userAlias, "userAlias");
		DtoValidation.requireNonNull(status, "status");
		DtoValidation.requireNonNull(summary, "summary");
		caseIds = DtoValidation.immutableList(caseIds, "caseIds");
		DtoValidation.requireNonNull(createdAt, "createdAt");
	}
}
