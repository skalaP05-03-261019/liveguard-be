package com.lifeguards.liveguard.contracts.dto.event;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Visibility;

public record ChatEvent(
		UUID messageId,
		UUID liveId,
		String authorAlias,
		String text,
		Visibility visibility,
		int version,
		Instant sentAt) {
	public ChatEvent {
		DtoValidation.requireNonNull(messageId, "messageId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(authorAlias, "authorAlias");
		DtoValidation.requireNonNull(text, "text");
		DtoValidation.requireNonNull(visibility, "visibility");
		DtoValidation.requireNonNull(sentAt, "sentAt");
	}
}
