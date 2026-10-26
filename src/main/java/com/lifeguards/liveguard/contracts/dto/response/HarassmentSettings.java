package com.lifeguards.liveguard.contracts.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.dto.HarassmentProfile;
import com.lifeguards.liveguard.contracts.enums.HarassmentLevel;

public record HarassmentSettings(UUID liveId, String policyVersion, String profileVersion,
		HarassmentLevel harassmentLevel, int settingsRevision, Instant changedAt) {
	public HarassmentSettings {
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(changedAt, "changedAt");
		HarassmentProfile.validateEvidence(2, policyVersion,
				new HarassmentProfile(harassmentLevel, profileVersion, settingsRevision));
	}
}
