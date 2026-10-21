package com.lifeguards.liveguard.contracts.dto.event;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ActionStatus;
import com.lifeguards.liveguard.contracts.enums.ActionType;
import com.lifeguards.liveguard.contracts.enums.Observation;

/** ACTION_APPLIED·ACTION_FAILED payload. observation=UNKNOWN이면 결과 미확인이며 실패로 표시하지 않는다. */
public record ActionEvent(UUID actionId, UUID liveId, ActionType type, ActionStatus status, Observation observation) {
	public ActionEvent {
		DtoValidation.requireNonNull(actionId, "actionId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(type, "type");
		DtoValidation.requireNonNull(status, "status");
		DtoValidation.requireNonNull(observation, "observation");
	}
}
