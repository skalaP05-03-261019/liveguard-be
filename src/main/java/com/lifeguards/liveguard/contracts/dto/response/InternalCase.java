package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.FilterResult;

public record InternalCase(
		UUID caseId,
		UUID channelId,
		FilterResult filterResult,
		List<String> matchingTags,
		int version,
		List<MaskedMessage> messages) {
	public InternalCase {
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(filterResult, "filterResult");
		matchingTags = DtoValidation.immutableList(matchingTags, "matchingTags");
		messages = DtoValidation.immutableList(messages, "messages");
	}
}
