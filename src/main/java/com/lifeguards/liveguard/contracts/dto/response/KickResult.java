package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.enums.KickReviewStatus;

public record KickResult(
		UUID reviewId,
		KickReviewStatus status,
		Instant startsAt,
		Instant expiresAt,
		boolean replayed) {
	public KickResult {
		Objects.requireNonNull(reviewId, "reviewId");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(startsAt, "startsAt");
		Objects.requireNonNull(expiresAt, "expiresAt");
	}
}
