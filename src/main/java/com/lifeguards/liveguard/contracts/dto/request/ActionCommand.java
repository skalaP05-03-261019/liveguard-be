package com.lifeguards.liveguard.contracts.dto.request;

import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ActionType;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record ActionCommand(
		UUID actionId,
		UUID planId,
		UUID caseId,
		ActionType type,
		UUID targetId,
		int expectedEvidenceVersion,
		int expectedTargetVersion,
		String policyVersion,
		String idempotencyKey,
		Integer durationSeconds,
		UUID decisionId,
		UUID originalActionId) {
	private static final int MAX_TIMEOUT_SECONDS = 604_800;

	public ActionCommand {
		Objects.requireNonNull(actionId, "actionId");
		Objects.requireNonNull(planId, "planId");
		Objects.requireNonNull(caseId, "caseId");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(targetId, "targetId");
		DtoValidation.requirePositive(expectedEvidenceVersion, "expectedEvidenceVersion");
		DtoValidation.requireNonNegative(expectedTargetVersion, "expectedTargetVersion");
		policyVersion = DtoValidation.requireText(policyVersion, "policyVersion");
		idempotencyKey = DtoValidation.requireText(idempotencyKey, "idempotencyKey");

		if (type == ActionType.TIMEOUT) {
			if (durationSeconds == null || durationSeconds <= 0 || durationSeconds > MAX_TIMEOUT_SECONDS) {
				throw new InvalidContractException(
						"TIMEOUT durationSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
			}
		} else if (durationSeconds != null) {
			throw new InvalidContractException("durationSeconds is only valid for TIMEOUT");
		}

		if (type == ActionType.RECONCILE_REVIEW) {
			Objects.requireNonNull(decisionId, "RECONCILE_REVIEW requires decisionId");
			Objects.requireNonNull(originalActionId, "RECONCILE_REVIEW requires originalActionId");
		} else if (decisionId != null || originalActionId != null) {
			throw new InvalidContractException(
					"decisionId and originalActionId are only valid for RECONCILE_REVIEW");
		}
	}
}
