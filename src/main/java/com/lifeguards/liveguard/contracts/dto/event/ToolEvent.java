package com.lifeguards.liveguard.contracts.dto.event;

import java.util.Objects;
import java.util.UUID;

/** status, elapsedMs는 TOOL_END에만 채운다. */
public record ToolEvent(UUID caseId, String tool, String callId, String status, Integer elapsedMs) {
	public ToolEvent {
		Objects.requireNonNull(caseId, "caseId");
		Objects.requireNonNull(tool, "tool");
		Objects.requireNonNull(callId, "callId");
	}
}
