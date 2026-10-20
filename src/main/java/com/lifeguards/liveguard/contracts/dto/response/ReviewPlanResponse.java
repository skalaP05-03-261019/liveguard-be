package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.dto.request.ActionCommand;

public record ReviewPlanResponse(List<UUID> affectedFindingIds, List<ActionCommand> actions) {
	public ReviewPlanResponse {
		affectedFindingIds = DtoValidation.immutableList(affectedFindingIds, "affectedFindingIds");
		actions = DtoValidation.immutableList(actions, "actions");
	}
}
