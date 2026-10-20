package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.TriState;

public record SecurityFact(
		UUID messageId,
		String patternType,
		TriState actualPersonalData,
		TriState officialBusinessMatch,
		String ownership,
		String source,
		Instant verifiedAt) {
	public SecurityFact {
		Objects.requireNonNull(messageId, "messageId");
		patternType = DtoValidation.requireText(patternType, "patternType");
		Objects.requireNonNull(actualPersonalData, "actualPersonalData");
		Objects.requireNonNull(officialBusinessMatch, "officialBusinessMatch");
		ownership = DtoValidation.requireText(ownership, "ownership");
		source = DtoValidation.requireText(source, "source");
	}
}
