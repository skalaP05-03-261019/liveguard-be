package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ActionStatus;
import com.lifeguards.liveguard.contracts.enums.ActionType;

public record ActionResult(
		UUID actionId,
		UUID caseId,
		ActionType type,
		ActionStatus status,
		String observation,
		boolean applied,
		String outcome,
		int targetVersion,
		Instant startedAt,
		Instant expiresAt,
		UUID linkedActionId,
		List<String> remainingReasons,
		Boolean replayed,
		ActionError error) {
	public ActionResult {
		Objects.requireNonNull(actionId, "actionId");
		Objects.requireNonNull(caseId, "caseId");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(status, "status");
		observation = DtoValidation.requireText(observation, "observation");
		outcome = DtoValidation.requireText(outcome, "outcome");
		DtoValidation.requireNonNegative(targetVersion, "targetVersion");
		remainingReasons = remainingReasons == null
				? null
				: DtoValidation.immutableTextList(remainingReasons, "remainingReasons");
	}
}
