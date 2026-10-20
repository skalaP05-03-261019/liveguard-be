package com.lifeguards.liveguard.contracts.dto.request;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record MessageFinding(
		UUID messageId,
		Verdict verdict,
		List<Category> categories,
		List<String> policyIds,
		List<UUID> evidenceMessageIds,
		String reason,
		List<String> missingFacts) {
	public MessageFinding {
		Objects.requireNonNull(messageId, "messageId");
		Objects.requireNonNull(verdict, "verdict");
		categories = DtoValidation.immutableList(categories, "categories");
		policyIds = DtoValidation.immutableTextList(policyIds, "policyIds");
		evidenceMessageIds = DtoValidation.immutableList(evidenceMessageIds, "evidenceMessageIds");
		reason = DtoValidation.requireText(reason, "reason");
		missingFacts = DtoValidation.immutableTextList(missingFacts, "missingFacts");

		if (verdict == Verdict.VIOLATION
				&& (categories.isEmpty() || policyIds.isEmpty() || evidenceMessageIds.isEmpty())) {
			throw new InvalidContractException(
					"VIOLATION requires categories, policyIds, and evidenceMessageIds");
		}
		if (verdict == Verdict.NO_VIOLATION && policyIds.isEmpty()) {
			throw new InvalidContractException("NO_VIOLATION requires an applicable exception policy");
		}
		if (verdict == Verdict.NEEDS_REVIEW && missingFacts.isEmpty()) {
			throw new InvalidContractException("NEEDS_REVIEW requires missingFacts");
		}
	}
}
