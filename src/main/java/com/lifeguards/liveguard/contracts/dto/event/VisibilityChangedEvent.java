package com.lifeguards.liveguard.contracts.dto.event;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Visibility;

/** 이미 공개됐던 메시지를 가릴 때는 ID만, PUBLIC으로 복원할 때는 text·authorAlias·sentAt을 포함한다 (없는 필드는 생략). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VisibilityChangedEvent(
		UUID messageId,
		Visibility visibility,
		int version,
		String text,
		String authorAlias,
		Instant sentAt) {
	public VisibilityChangedEvent {
		DtoValidation.requireNonNull(messageId, "messageId");
		DtoValidation.requireNonNull(visibility, "visibility");
	}
}
