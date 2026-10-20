package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.dto.request.ActionCommand;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record CreatePlanResponse(
		UUID planId,
		List<ActionCommand> actions,
		Instant historyAsOf,
		Map<Category, Integer> countsByCategory) {
	public CreatePlanResponse {
		Objects.requireNonNull(planId, "planId");
		actions = DtoValidation.immutableList(actions, "actions");
		Objects.requireNonNull(historyAsOf, "historyAsOf");
		countsByCategory = DtoValidation.immutableMap(countsByCategory, "countsByCategory");
		countsByCategory.forEach((category, count) -> {
			Objects.requireNonNull(category, "countsByCategory category");
			if (count == null || count < 0) {
				throw new InvalidContractException("countsByCategory values must be non-negative");
			}
		});
	}
}
