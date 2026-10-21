package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/** 강퇴 승인 재검증 결과(A-11). valid=true면 kick 실행 명령에 넣을 validationRef가 있고, false면 null이다. */
public record KickRevalidation(
		UUID reviewId,
		UUID decisionId,
		boolean valid,
		UUID validationRef,
		List<UUID> basisCaseIds) {
	public KickRevalidation {
		DtoValidation.requireNonNull(reviewId, "reviewId");
		DtoValidation.requireNonNull(decisionId, "decisionId");
		if (valid != (validationRef != null)) {
			throw new InvalidContractException("validationRef is required only when valid");
		}
		basisCaseIds = DtoValidation.immutableList(basisCaseIds, "basisCaseIds");
		if (basisCaseIds.stream().distinct().count() != basisCaseIds.size()) {
			throw new InvalidContractException("basisCaseIds must contain distinct cases");
		}
	}
}
