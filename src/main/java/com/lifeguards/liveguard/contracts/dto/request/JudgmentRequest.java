package com.lifeguards.liveguard.contracts.dto.request;

import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/**
 * Agent 출력(CONTRACTS §5). AI 조사 경로(CONTEXT_REVIEW, PII_INVESTIGATION)만 제출하며 TARGET 메시지마다 결과가 하나다.
 * 공개·제재 명령이 아니다. policyVersion이 활성 버전과 같은지는 서버가 검증한다.
 */
public record JudgmentRequest(
		UUID caseId,
		UUID liveId,
		HandlingRoute handlingRoute,
		UUID snapshotId,
		int evidenceVersion,
		String policyVersion,
		List<MessageFinding> messageFindings) {
	public JudgmentRequest {
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(liveId, "liveId");
		DtoValidation.requireNonNull(handlingRoute, "handlingRoute");
		if (handlingRoute != HandlingRoute.CONTEXT_REVIEW && handlingRoute != HandlingRoute.PII_INVESTIGATION) {
			throw new InvalidContractException("Only CONTEXT_REVIEW and PII_INVESTIGATION are submitted");
		}
		DtoValidation.requireNonNull(snapshotId, "snapshotId");
		if (evidenceVersion < 1) {
			throw new InvalidContractException("evidenceVersion must be positive");
		}
		policyVersion = DtoValidation.requireText(policyVersion, "policyVersion");
		messageFindings = DtoValidation.immutableList(messageFindings, "messageFindings");
		DtoValidation.requireNotEmpty(messageFindings, "messageFindings");
		if (messageFindings.stream().map(MessageFinding::messageId).distinct().count() != messageFindings.size()) {
			throw new InvalidContractException("Each target message has exactly one finding");
		}
	}

	public record MessageFinding(
			UUID messageId,
			Verdict verdict,
			List<Category> categories,
			List<String> policyIds,
			String reason,
			List<String> missingFacts) {
		public MessageFinding {
			DtoValidation.requireNonNull(messageId, "messageId");
			DtoValidation.requireNonNull(verdict, "verdict");
			categories = DtoValidation.immutableList(categories, "categories");
			policyIds = DtoValidation.immutableList(policyIds, "policyIds");
			reason = DtoValidation.requireText(reason, "reason");
			missingFacts = DtoValidation.immutableList(missingFacts, "missingFacts");
			if (categories.stream().distinct().count() != categories.size()) {
				throw new InvalidContractException("categories must not repeat");
			}
			switch (verdict) {
				case VIOLATION -> {
					DtoValidation.requireNotEmpty(categories, "categories");
					DtoValidation.requireNotEmpty(policyIds, "policyIds");
				}
				case NEEDS_REVIEW -> DtoValidation.requireNotEmpty(missingFacts, "missingFacts");
				case NO_VIOLATION -> {
					if (!categories.isEmpty()) {
						throw new InvalidContractException("NO_VIOLATION must not carry categories");
					}
				}
			}
		}
	}
}
