package com.lifeguards.liveguard.contracts.dto.request;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ReviewDecision;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record ReviewDecisionRequest(
		UUID clientRequestId,
		int expectedAppealVersion,
		ReviewDecision decision,
		String reason,
		List<UUID> findingIds,
		Integer durationSeconds) {
	public ReviewDecisionRequest {
		Objects.requireNonNull(clientRequestId, "clientRequestId");
		DtoValidation.requireNonNegative(expectedAppealVersion, "expectedAppealVersion");
		Objects.requireNonNull(decision, "decision");
		reason = DtoValidation.requireText(reason, "reason");
		findingIds = findingIds == null ? null : DtoValidation.immutableList(findingIds, "findingIds");

		switch (decision) {
			case REVOKE_FINDING -> {
				if (findingIds == null || findingIds.isEmpty() || durationSeconds != null) {
					throw new InvalidContractException(
							"REVOKE_FINDING requires findingIds and forbids durationSeconds");
				}
			}
			case REDUCE_ACTION -> {
				if (durationSeconds == null || durationSeconds < 0 || findingIds != null) {
					throw new InvalidContractException(
							"REDUCE_ACTION requires non-negative durationSeconds and forbids findingIds");
				}
			}
			case UPHOLD -> {
				if (findingIds != null || durationSeconds != null) {
					throw new InvalidContractException(
							"UPHOLD does not accept findingIds or durationSeconds");
				}
			}
		}
	}
}
