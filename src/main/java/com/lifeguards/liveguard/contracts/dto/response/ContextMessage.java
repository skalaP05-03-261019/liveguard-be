package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record ContextMessage(UUID messageId, String authorAlias, String text, Instant sentAt, UUID replyToId) {
	public ContextMessage {
		Objects.requireNonNull(messageId, "messageId");
		authorAlias = DtoValidation.requireText(authorAlias, "authorAlias");
		text = DtoValidation.requireText(text, "text");
		Objects.requireNonNull(sentAt, "sentAt");
	}
}
