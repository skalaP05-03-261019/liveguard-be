package com.lifeguards.liveguard.contracts.dto.request;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.dto.response.PolicySnapshot;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record JudgmentRequest(
		int expectedVersion,
		Verdict verdict,
		Category category,
		List<UUID> evidenceMessageIds,
		List<String> policyIds,
		List<PolicySnapshot> policySnapshots,
		String reason,
		List<String> missingFacts,
		String traceId) {
	public JudgmentRequest {
		Objects.requireNonNull(verdict, "verdict");
		evidenceMessageIds = DtoValidation.immutableList(evidenceMessageIds, "evidenceMessageIds");
		policyIds = DtoValidation.immutableList(policyIds, "policyIds");
		policySnapshots = DtoValidation.immutableListOrEmpty(policySnapshots);
		reason = DtoValidation.requireText(reason, "reason");
		missingFacts = DtoValidation.immutableListOrEmpty(missingFacts);
		Objects.requireNonNull(traceId, "traceId");
		switch (verdict) {
			case VIOLATION -> {
				if (category == null) {
					throw new InvalidContractException("VIOLATION requires category");
				}
				DtoValidation.requireNotEmpty(evidenceMessageIds, "evidenceMessageIds");
				DtoValidation.requireNotEmpty(policyIds, "policyIds");
			}
			case NEEDS_REVIEW -> DtoValidation.requireNotEmpty(missingFacts, "missingFacts");
			case NO_VIOLATION -> {
			}
		}
	}
}
