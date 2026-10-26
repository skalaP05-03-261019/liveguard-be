package com.lifeguards.liveguard.contracts.dto;

import com.lifeguards.liveguard.contracts.enums.HarassmentLevel;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record HarassmentProfile(HarassmentLevel level, String profileVersion, int settingsRevision) {
	public static final String PROFILE_VERSION = "harassment-profile-v1";
	public static final String POLICY_VERSION = "v3.3";

	public HarassmentProfile {
		DtoValidation.requireNonNull(level, "level");
		if (!PROFILE_VERSION.equals(profileVersion)) {
			throw new InvalidContractException("Unsupported profileVersion");
		}
		if (settingsRevision < 1) {
			throw new InvalidContractException("settingsRevision must be positive");
		}
	}

	/** 버전 표시가 없는 기존 snapshot은 구 계약이며 신규 계약의 필드 누락을 보완하지 않는다. */
	public static void validateEvidence(Integer evidenceSchemaVersion, String policyVersion,
			HarassmentProfile profile) {
		if (evidenceSchemaVersion != null && evidenceSchemaVersion != 1 && evidenceSchemaVersion != 2) {
			throw new InvalidContractException("Unsupported evidenceSchemaVersion");
		}
		boolean profiled = Integer.valueOf(2).equals(evidenceSchemaVersion);
		if (profiled != (profile != null) || profiled != POLICY_VERSION.equals(policyVersion)) {
			throw new InvalidContractException("Schema 2 requires v3.3 and harassmentProfile; legacy evidence forbids both");
		}
	}
}
