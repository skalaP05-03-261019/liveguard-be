package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Fixed approved review basis, not new eligibility. Consumption ownership must be revalidated before execution.")
public record KickReviewBasis(
		UUID reviewId,
		UUID decisionId,
		UUID liveId,
		UUID userId,
		UUID basisSnapshotId,
		List<UUID> basisCaseIds,
		List<Consumption> consumptions) {
	public KickReviewBasis {
		DtoValidation.requireNonNull(reviewId, "reviewId");
		DtoValidation.requireNonNull(decisionId, "decisionId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(userId, "userId");
		DtoValidation.requireNonNull(basisSnapshotId, "basisSnapshotId");
		basisCaseIds = DtoValidation.immutableList(basisCaseIds, "basisCaseIds");
		if (basisCaseIds.size() < 3 || basisCaseIds.stream().distinct().count() != basisCaseIds.size()) {
			throw new InvalidContractException("basisCaseIds must contain at least three distinct cases");
		}
		consumptions = DtoValidation.immutableList(consumptions, "consumptions");
		for (Consumption consumption : consumptions) {
			if (!basisCaseIds.contains(consumption.caseId())) {
				throw new InvalidContractException("Consumption must reference a basis case");
			}
		}
	}

	public record Consumption(UUID caseId, UUID reviewId, UUID decisionId) {
		public Consumption {
			DtoValidation.requireNonNull(caseId, "caseId");
			DtoValidation.requireNonNull(reviewId, "reviewId");
			DtoValidation.requireNonNull(decisionId, "decisionId");
		}
	}
}
