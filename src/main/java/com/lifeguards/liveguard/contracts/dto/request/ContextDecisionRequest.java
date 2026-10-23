package com.lifeguards.liveguard.contracts.dto.request;

import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.ContextDecision;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/**
 * 문맥 검토 결정(A-7). CONFIRM_VIOLATION이면 categories·evidenceMessageIds가 필수이고, 다른 결정에서는 보내지 않는다.
 * reason은 선택이다(A-26).
 */
public record ContextDecisionRequest(
		UUID clientRequestId,
		int expectedReviewVersion,
		ContextDecision decision,
		String reason,
		List<Category> categories,
		List<UUID> evidenceMessageIds) {

	private static final int MAX_REASON_LENGTH = 500;

	public ContextDecisionRequest {
		DtoValidation.requireNonNull(clientRequestId, "clientRequestId");
		DtoValidation.requireNonNull(decision, "decision");
		reason = DtoValidation.optionalBoundedText(reason, "reason", MAX_REASON_LENGTH);
		if (decision == ContextDecision.CONFIRM_VIOLATION) {
			categories = DtoValidation.immutableList(categories, "categories");
			evidenceMessageIds = DtoValidation.immutableList(evidenceMessageIds, "evidenceMessageIds");
			DtoValidation.requireNotEmpty(categories, "categories");
			DtoValidation.requireNotEmpty(evidenceMessageIds, "evidenceMessageIds");
			if (categories.stream().distinct().count() != categories.size()
					|| evidenceMessageIds.stream().distinct().count() != evidenceMessageIds.size()) {
				throw new InvalidContractException("categories and evidenceMessageIds must not repeat");
			}
		} else if (categories != null || evidenceMessageIds != null) {
			throw new InvalidContractException("categories and evidenceMessageIds are only for CONFIRM_VIOLATION");
		}
	}
}
