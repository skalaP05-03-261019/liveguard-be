package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MaskedMessage(UUID messageId, String authorAlias, String text, Instant sentAt) {
	public MaskedMessage {
		Objects.requireNonNull(messageId, "messageId");
		Objects.requireNonNull(authorAlias, "authorAlias");
		Objects.requireNonNull(text, "text");
		Objects.requireNonNull(sentAt, "sentAt");
	}
}
