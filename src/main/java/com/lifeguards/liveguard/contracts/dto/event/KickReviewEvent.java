package com.lifeguards.liveguard.contracts.dto.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.enums.KickReviewStatus;

public record KickReviewEvent(
		UUID reviewId,
		UUID caseId,
		KickReviewStatus status,
		Instant kickExpiresAt) {
	public KickReviewEvent {
		Objects.requireNonNull(reviewId, "reviewId");
		Objects.requireNonNull(caseId, "caseId");
		Objects.requireNonNull(status, "status");
	}
}
