package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record MaskedMessage(UUID messageId, String authorAlias, String text, Instant sentAt) {
	public MaskedMessage {
		DtoValidation.requireNonNull(messageId, "messageId");
		DtoValidation.requireNonNull(authorAlias, "authorAlias");
		DtoValidation.requireNonNull(text, "text");
		DtoValidation.requireNonNull(sentAt, "sentAt");
	}
}
