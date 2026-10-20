package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record SecurityFactsResponse(List<SecurityFact> facts) {
	public SecurityFactsResponse {
		facts = DtoValidation.immutableList(facts, "facts");
	}
}
