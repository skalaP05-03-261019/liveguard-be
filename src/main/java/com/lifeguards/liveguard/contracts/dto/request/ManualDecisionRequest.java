package com.lifeguards.liveguard.contracts.dto.request;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.ManualDecision;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record ManualDecisionRequest(
		UUID clientRequestId,
		int expectedEvidenceVersion,
		ManualDecision decision,
		List<MessageFinding> messageFindings,
		String reason) {
	public ManualDecisionRequest {
		Objects.requireNonNull(clientRequestId, "clientRequestId");
		DtoValidation.requirePositive(expectedEvidenceVersion, "expectedEvidenceVersion");
		Objects.requireNonNull(decision, "decision");
		messageFindings = DtoValidation.immutableList(messageFindings, "messageFindings");
		reason = DtoValidation.requireText(reason, "reason");
		long distinctIds = messageFindings.stream().map(MessageFinding::messageId).distinct().count();
		if (distinctIds != messageFindings.size()) {
			throw new InvalidContractException("messageFindings must contain each messageId at most once");
		}
	}
}
