package com.lifeguards.liveguard.contracts.dto.request;

import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record SendMessageRequest(UUID clientMessageId, String text) {
	private static final int MAX_TEXT_LENGTH = 500;

	public SendMessageRequest {
		Objects.requireNonNull(clientMessageId, "clientMessageId");
		text = DtoValidation.requireBoundedText(text, "text", MAX_TEXT_LENGTH);
	}
}
