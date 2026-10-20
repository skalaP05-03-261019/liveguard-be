package com.lifeguards.liveguard.contracts.dto.event;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.KickReviewStatus;

public record KickReviewEvent(
		UUID reviewId,
		UUID caseId,
		KickReviewStatus status,
		Instant kickExpiresAt) {
	public KickReviewEvent {
		DtoValidation.requireNonNull(reviewId, "reviewId");
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(status, "status");
	}
}
