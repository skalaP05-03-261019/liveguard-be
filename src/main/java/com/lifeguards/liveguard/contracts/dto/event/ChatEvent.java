package com.lifeguards.liveguard.contracts.dto.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.enums.Visibility;

public record ChatEvent(
		UUID messageId,
		String authorAlias,
		String text,
		Visibility visibility,
		int version,
		Instant sentAt) {
	public ChatEvent {
		Objects.requireNonNull(messageId, "messageId");
		Objects.requireNonNull(authorAlias, "authorAlias");
		Objects.requireNonNull(text, "text");
		Objects.requireNonNull(visibility, "visibility");
		Objects.requireNonNull(sentAt, "sentAt");
	}
}
