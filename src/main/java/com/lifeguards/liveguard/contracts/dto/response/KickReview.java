package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ReviewKind;
import com.lifeguards.liveguard.contracts.enums.ReviewStatus;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/**
 * 강퇴 승인 검토. basisCaseIds는 심사 요청 때 고정한 기준 사건이다.
 * 결정 상태(status)와 실제 강퇴 결과(ACTION_*)는 따로 보며, 강퇴는 방송 종료까지라 만료 시각이 없다.
 */
public record KickReview(
		UUID reviewId,
		ReviewKind kind,
		UUID liveId,
		String userAlias,
		List<UUID> basisCaseIds,
		UUID basisSnapshotId,
		ReviewStatus status,
		String summary,
		String decisionReason,
		int version,
		Instant createdAt) implements Review {
	public KickReview {
		DtoValidation.requireNonNull(reviewId, "reviewId");
		if (kind != ReviewKind.KICK) {
			throw new InvalidContractException("KickReview kind must be KICK");
		}
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(userAlias, "userAlias");
		basisCaseIds = DtoValidation.immutableList(basisCaseIds, "basisCaseIds");
		if (basisCaseIds.size() < 3 || basisCaseIds.stream().distinct().count() != basisCaseIds.size()) {
			throw new InvalidContractException("basisCaseIds must contain at least three distinct cases");
		}
		DtoValidation.requireNonNull(basisSnapshotId, "basisSnapshotId");
		DtoValidation.requireNonNull(status, "status");
		DtoValidation.requireNonNull(summary, "summary");
		DtoValidation.requireNonNull(createdAt, "createdAt");
	}
}
