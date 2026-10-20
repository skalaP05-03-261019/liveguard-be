package com.lifeguards.liveguard.contracts.dto.response;

import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record OfficialLink(UUID messageId, String host, boolean isOfficial, boolean eventMatched) {
	public OfficialLink {
		Objects.requireNonNull(messageId, "messageId");
		host = DtoValidation.requireText(host, "host");
	}
}
