package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Category;

/** 사건의 BE 확정 위반 (openapi.yaml ConfirmedFinding). status는 CONFIRMED 또는 REVOKED. */
public record ConfirmedFinding(UUID findingId, Category category, String status, Instant confirmedAt, Instant revokedAt) {
	public ConfirmedFinding {
		DtoValidation.requireNonNull(findingId, "findingId");
		DtoValidation.requireNonNull(category, "category");
		DtoValidation.requireNonNull(status, "status");
	}
}
