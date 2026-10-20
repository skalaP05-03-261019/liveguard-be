package com.lifeguards.liveguard.contracts.dto.event;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

/** status, elapsedMs는 TOOL_END에만 채운다. */
public record ToolEvent(UUID caseId, String tool, String callId, String status, Integer elapsedMs) {
	public ToolEvent {
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(tool, "tool");
		DtoValidation.requireNonNull(callId, "callId");
	}
}
