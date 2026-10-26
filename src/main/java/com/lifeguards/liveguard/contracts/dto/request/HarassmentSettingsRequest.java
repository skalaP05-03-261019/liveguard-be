package com.lifeguards.liveguard.contracts.dto.request;

import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.HarassmentLevel;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record HarassmentSettingsRequest(UUID clientRequestId, int expectedSettingsRevision,
		HarassmentLevel harassmentLevel) {
	public HarassmentSettingsRequest {
		DtoValidation.requireNonNull(clientRequestId, "clientRequestId");
		DtoValidation.requireNonNull(harassmentLevel, "harassmentLevel");
		if (expectedSettingsRevision < 1) {
			throw new InvalidContractException("expectedSettingsRevision must be positive");
		}
	}
}
