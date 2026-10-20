package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Visibility;

public record Message(
		UUID messageId,
		UUID channelId,
		String authorAlias,
		String text,
		Visibility visibility,
		int version,
		Instant sentAt,
		boolean isMine) {
	public Message {
		DtoValidation.requireNonNull(messageId, "messageId");
		DtoValidation.requireNonNull(channelId, "channelId");
		DtoValidation.requireNonNull(authorAlias, "authorAlias");
		DtoValidation.requireNonNull(text, "text");
		DtoValidation.requireNonNull(visibility, "visibility");
		DtoValidation.requireNonNull(sentAt, "sentAt");
	}
}
