package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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
		Objects.requireNonNull(messageId, "messageId");
		Objects.requireNonNull(channelId, "channelId");
		Objects.requireNonNull(authorAlias, "authorAlias");
		Objects.requireNonNull(text, "text");
		Objects.requireNonNull(visibility, "visibility");
		Objects.requireNonNull(sentAt, "sentAt");
	}
}
