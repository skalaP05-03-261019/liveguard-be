package com.lifeguards.liveguard.contracts.dto.response;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

public record PolicySnapshot(
		String policyId,
		String version,
		String title,
		String text,
		String resourceUri) {
	public PolicySnapshot {
		DtoValidation.requireNonNull(policyId, "policyId");
		DtoValidation.requireNonNull(version, "version");
		DtoValidation.requireNonNull(title, "title");
		DtoValidation.requireNonNull(text, "text");
		DtoValidation.requireNonNull(resourceUri, "resourceUri");
	}
}
