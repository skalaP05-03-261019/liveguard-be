package com.lifeguards.liveguard.internal;

import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ActionType;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record BusinessActionCommand(
		UUID actionId,
		UUID planId,
		UUID caseId,
		UUID liveId,
		ActionType type,
		UUID targetId,
		int expectedEvidenceVersion,
		int expectedTargetVersion,
		String policyVersion,
		String idempotencyKey,
		Integer durationSeconds,
		UUID decisionId,
		UUID reviewId,
		UUID basisSnapshotId,
		UUID validationRef,
		List<UUID> basisCaseIds) {

	public BusinessActionCommand {
		DtoValidation.requireNonNull(actionId, "actionId");
		DtoValidation.requireNonNull(planId, "planId");
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(type, "type");
		DtoValidation.requireNonNull(targetId, "targetId");
		if (expectedEvidenceVersion <= 0) {
			throw new InvalidContractException("expectedEvidenceVersion must be positive");
		}
		if (expectedTargetVersion <= 0) {
			throw new InvalidContractException("expectedTargetVersion must be positive");
		}
		policyVersion = DtoValidation.requireText(policyVersion, "policyVersion");
		idempotencyKey = DtoValidation.requireBoundedText(idempotencyKey, "idempotencyKey", 200);
		basisCaseIds = DtoValidation.immutableListOrEmpty(basisCaseIds);
		if (type == ActionType.TIMEOUT) {
			if (durationSeconds == null || durationSeconds < 1) {
				throw new InvalidContractException("TIMEOUT requires positive durationSeconds");
			}
		} else if (durationSeconds != null) {
			throw new InvalidContractException("durationSeconds is only valid for TIMEOUT");
		}
		if (type == ActionType.KICK) {
			DtoValidation.requireNonNull(decisionId, "decisionId");
			DtoValidation.requireNonNull(reviewId, "reviewId");
			DtoValidation.requireNonNull(basisSnapshotId, "basisSnapshotId");
			DtoValidation.requireNonNull(validationRef, "validationRef");
			if (basisCaseIds.size() < 3 || basisCaseIds.stream().distinct().count() != basisCaseIds.size()) {
				throw new InvalidContractException("KICK requires at least three distinct basisCaseIds");
			}
		} else if (reviewId != null || basisSnapshotId != null || validationRef != null || !basisCaseIds.isEmpty()
				|| (decisionId != null && type != ActionType.RELEASE_HELD)) {
			throw new InvalidContractException("Kick basis fields are only valid for KICK; RELEASE_HELD may reference a decision");
		}
	}
}
