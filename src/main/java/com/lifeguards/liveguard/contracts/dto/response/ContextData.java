package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record ContextData(List<MaskedMessage> messages, boolean truncated) {
	private static final int MAX_MESSAGES = 20;

	public ContextData {
		messages = DtoValidation.immutableList(messages, "messages");
		DtoValidation.requireMaxSize(messages, MAX_MESSAGES, "messages");
	}
}
