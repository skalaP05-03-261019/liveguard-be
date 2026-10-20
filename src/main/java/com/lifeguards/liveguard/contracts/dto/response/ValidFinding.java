package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.enums.Category;

public record ValidFinding(UUID findingId, Category category, Instant occurredAt) {
	public ValidFinding {
		Objects.requireNonNull(findingId, "findingId");
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(occurredAt, "occurredAt");
	}
}
