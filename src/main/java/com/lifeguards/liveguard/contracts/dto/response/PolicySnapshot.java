package com.lifeguards.liveguard.contracts.dto.response;

import java.util.Objects;

public record PolicySnapshot(
		String policyId,
		String version,
		String title,
		String text,
		String resourceUri) {
	public PolicySnapshot {
		Objects.requireNonNull(policyId, "policyId");
		Objects.requireNonNull(version, "version");
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(text, "text");
		Objects.requireNonNull(resourceUri, "resourceUri");
	}
}
