package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record ContextData(List<MaskedMessage> messages, boolean truncated,
        java.util.UUID caseId, java.util.UUID snapshotId, Integer evidenceVersion) {
	public ContextData(List<MaskedMessage> messages, boolean truncated) { this(messages,truncated,null,null,null); }
	private static final int MAX_MESSAGES = 20;

	public ContextData {
		messages = DtoValidation.immutableList(messages, "messages");
		DtoValidation.requireMaxSize(messages, MAX_MESSAGES, "messages");
	}
}
