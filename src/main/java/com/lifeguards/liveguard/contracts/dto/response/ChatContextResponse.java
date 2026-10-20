package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record ChatContextResponse(List<ContextMessage> messages, boolean truncated) {
	public ChatContextResponse {
		messages = DtoValidation.immutableList(messages, "messages");
		if (messages.size() > 20) {
			throw new InvalidContractException("messages cannot exceed 20 items");
		}
	}
}
