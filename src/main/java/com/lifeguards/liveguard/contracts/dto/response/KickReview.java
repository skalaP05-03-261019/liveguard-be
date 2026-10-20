package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
		Objects.requireNonNull(reviewId, "reviewId");
		Objects.requireNonNull(channelId, "channelId");
		Objects.requireNonNull(userAlias, "userAlias");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(summary, "summary");
		caseIds = DtoValidation.immutableList(caseIds, "caseIds");
		Objects.requireNonNull(createdAt, "createdAt");
	}
}
