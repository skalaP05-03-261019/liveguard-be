package com.lifeguards.liveguard.contracts.dto.request;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.PlanMode;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record CreatePlanRequest(
		UUID caseId,
		UUID snapshotId,
		int evidenceVersion,
		String policyVersion,
		PlanMode mode,
		List<MessageFinding> messageFindings,
		String summary) {
	public CreatePlanRequest {
		Objects.requireNonNull(caseId, "caseId");
		Objects.requireNonNull(snapshotId, "snapshotId");
		DtoValidation.requirePositive(evidenceVersion, "evidenceVersion");
		policyVersion = DtoValidation.requireText(policyVersion, "policyVersion");
		Objects.requireNonNull(mode, "mode");
		messageFindings = DtoValidation.immutableList(messageFindings, "messageFindings");
		summary = DtoValidation.requireText(summary, "summary");
		long distinctIds = messageFindings.stream().map(MessageFinding::messageId).distinct().count();
		if (distinctIds != messageFindings.size()) {
			throw new InvalidContractException("messageFindings must contain each messageId at most once");
		}
	}
}
