package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.KickReviewStatus;

public record KickResult(
		UUID reviewId,
		KickReviewStatus status,
		Instant startsAt,
		Instant expiresAt,
		boolean replayed) {
	public KickResult {
		DtoValidation.requireNonNull(reviewId, "reviewId");
		DtoValidation.requireNonNull(status, "status");
		DtoValidation.requireNonNull(startsAt, "startsAt");
		DtoValidation.requireNonNull(expiresAt, "expiresAt");
	}
}
