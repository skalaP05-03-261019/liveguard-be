package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ActionStatus;
import com.lifeguards.liveguard.contracts.enums.ActionType;
import com.lifeguards.liveguard.contracts.enums.Observation;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
import io.swagger.v3.oas.annotations.media.Schema;

public record ActionExecutionResult(
		UUID actionId,
		UUID caseId,
		UUID liveId,
		ActionType type,
		ActionStatus status,
		Observation observation,
		@Schema(nullable = true, description = "null when observation is UNKNOWN") Boolean applied,
		int attemptCount,
		int retryCount,
		Instant startedAt,
		Instant expiresAt,
		String errorCode) {

	public ActionExecutionResult {
		DtoValidation.requireNonNull(actionId, "actionId");
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(type, "type");
		DtoValidation.requireNonNull(status, "status");
		DtoValidation.requireNonNull(observation, "observation");
		if ((observation == Observation.UNKNOWN && applied != null)
				|| (observation == Observation.CONFIRMED && applied == null)) {
			throw new InvalidContractException("UNKNOWN requires applied=null; CONFIRMED requires applied");
		}
		if (observation == Observation.UNKNOWN && (status == ActionStatus.SUCCEEDED || status == ActionStatus.FAILED)) {
			throw new InvalidContractException("UNKNOWN must not claim terminal success or failure");
		}
		if (status == ActionStatus.SUCCEEDED && !Boolean.TRUE.equals(applied)) {
			throw new InvalidContractException("SUCCEEDED requires applied=true");
		}
		if (status == ActionStatus.FAILED && !Boolean.FALSE.equals(applied)) {
			throw new InvalidContractException("FAILED requires applied=false");
		}
		if (attemptCount < 0 || retryCount != Math.max(0, attemptCount - 1)
				|| (type == ActionType.KICK && attemptCount > 3)) {
			throw new InvalidContractException("Invalid persistent attempt/retry counts");
		}
		if (type == ActionType.KICK && expiresAt != null) {
			throw new InvalidContractException("KICK ends with the live, not a fixed expiresAt");
		}
	}
}
