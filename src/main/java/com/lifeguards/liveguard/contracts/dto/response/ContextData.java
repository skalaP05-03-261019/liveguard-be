package com.lifeguards.liveguard.contracts.dto.response;

import java.util.List;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.dto.HarassmentProfile;
import com.fasterxml.jackson.annotation.JsonInclude;

public record ContextData(List<MaskedMessage> messages, boolean truncated,
        java.util.UUID caseId, java.util.UUID snapshotId, Integer evidenceVersion,
        @JsonInclude(JsonInclude.Include.NON_NULL) String policyVersion,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer evidenceSchemaVersion,
        @JsonInclude(JsonInclude.Include.NON_NULL) HarassmentProfile harassmentProfile) {
	public ContextData(List<MaskedMessage> messages, boolean truncated, java.util.UUID caseId,
			java.util.UUID snapshotId, Integer evidenceVersion) {
		this(messages, truncated, caseId, snapshotId, evidenceVersion, null, null, null);
	}
	public ContextData(List<MaskedMessage> messages, boolean truncated) { this(messages,truncated,null,null,null); }
	private static final int MAX_MESSAGES = 20;

	public ContextData {
		messages = DtoValidation.immutableList(messages, "messages");
		DtoValidation.requireMaxSize(messages, MAX_MESSAGES, "messages");
		HarassmentProfile.validateEvidence(evidenceSchemaVersion, policyVersion, harassmentProfile);
		if (harassmentProfile != null) {
			DtoValidation.requireNonNull(caseId, "caseId");
			DtoValidation.requireNonNull(snapshotId, "snapshotId");
			if (evidenceVersion == null || evidenceVersion < 1) {
				throw new com.lifeguards.liveguard.contracts.exception.InvalidContractException("evidenceVersion must be positive");
			}
		}
	}
}
