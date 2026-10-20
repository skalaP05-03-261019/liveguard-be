package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.FilterResult;
import com.lifeguards.liveguard.contracts.enums.Visibility;

public record ModerationCaseResponse(
		UUID snapshotId,
		int evidenceVersion,
		FilterResult filterResult,
		List<UUID> messageIds,
		Map<UUID, Visibility> visibilityByMessage,
		List<ActionResult> actions) {
	public ModerationCaseResponse {
		Objects.requireNonNull(snapshotId, "snapshotId");
		DtoValidation.requirePositive(evidenceVersion, "evidenceVersion");
		Objects.requireNonNull(filterResult, "filterResult");
		messageIds = DtoValidation.immutableList(messageIds, "messageIds");
		visibilityByMessage = DtoValidation.immutableMap(visibilityByMessage, "visibilityByMessage");
		actions = DtoValidation.immutableList(actions, "actions");
	}
}
