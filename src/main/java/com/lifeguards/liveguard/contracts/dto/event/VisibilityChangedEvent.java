package com.lifeguards.liveguard.contracts.dto.event;

import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.enums.Visibility;

/** 이미 공개됐던 메시지를 가릴 때는 ID만, PUBLIC으로 복원할 때는 text를 포함한다. */
public record VisibilityChangedEvent(
		UUID messageId,
		Visibility visibility,
		int version,
		String text,
		String authorAlias) {
	public VisibilityChangedEvent {
		Objects.requireNonNull(messageId, "messageId");
		Objects.requireNonNull(visibility, "visibility");
	}
}
