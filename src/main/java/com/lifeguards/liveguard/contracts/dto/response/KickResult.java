package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.KickReviewStatus;

/** 레거시 /internal/kick-reviews/{reviewId}/execute 전용(24시간 강퇴). v3.1에 없으며 삭제는 B2 확인 후(A-12). */
@Deprecated
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
