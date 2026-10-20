package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.Objects;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record StreamEventSummary(String eventType, Instant occurredAt, String summary) {
	public StreamEventSummary {
		eventType = DtoValidation.requireText(eventType, "eventType");
		Objects.requireNonNull(occurredAt, "occurredAt");
		summary = DtoValidation.requireText(summary, "summary");
	}
}
