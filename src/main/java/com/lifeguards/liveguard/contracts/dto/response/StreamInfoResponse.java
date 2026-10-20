package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record StreamInfoResponse(UUID channelId, List<StreamEventSummary> eventSummaries,
		List<String> officialDomains) {
	public StreamInfoResponse {
		Objects.requireNonNull(channelId, "channelId");
		eventSummaries = DtoValidation.immutableList(eventSummaries, "eventSummaries");
		officialDomains = DtoValidation.immutableTextList(officialDomains, "officialDomains");
	}
}
