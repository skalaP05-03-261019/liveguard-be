package com.lifeguards.liveguard.contracts.dto.request;

import java.util.List;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.dto.HarassmentAssessment;
import com.lifeguards.liveguard.contracts.dto.HarassmentProfile;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeguards.liveguard.contracts.enums.Category;
import com.lifeguards.liveguard.contracts.enums.HandlingRoute;
import com.lifeguards.liveguard.contracts.enums.HarassmentLevel;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

/**
 * Agent 출력(CONTRACTS §5). AI 조사 경로(CONTEXT_REVIEW, PII_INVESTIGATION)만 제출하며 TARGET 메시지마다 결과가 하나다.
 * 공개·제재 명령이 아니다. policyVersion이 사건에 기록된 버전과 같은지, policyIds가 그 버전의 조항인지는 서버가 검증한다(A-27).
 */
public record JudgmentRequest(
		UUID caseId,
		UUID liveId,
		HandlingRoute handlingRoute,
		UUID snapshotId,
		int evidenceVersion,
		String policyVersion,
		List<MessageFinding> messageFindings,
		@JsonInclude(JsonInclude.Include.NON_NULL) Integer evidenceSchemaVersion,
		@JsonInclude(JsonInclude.Include.NON_NULL) HarassmentProfile harassmentProfile) {
	public JudgmentRequest(UUID caseId, UUID liveId, HandlingRoute handlingRoute, UUID snapshotId,
			int evidenceVersion, String policyVersion, List<MessageFinding> messageFindings) {
		this(caseId, liveId, handlingRoute, snapshotId, evidenceVersion, policyVersion, messageFindings, null, null);
	}

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
		HarassmentProfile.validateEvidence(evidenceSchemaVersion, policyVersion, harassmentProfile);
		messageFindings = DtoValidation.immutableList(messageFindings, "messageFindings");
		DtoValidation.requireNotEmpty(messageFindings, "messageFindings");
		if (messageFindings.stream().map(MessageFinding::messageId).distinct().count() != messageFindings.size()) {
			throw new InvalidContractException("Each target message has exactly one finding");
		}
		for (MessageFinding finding : messageFindings) {
			boolean evaluated = finding.categories().contains(Category.HARASSMENT)
					|| finding.policyIds().stream().anyMatch(id -> List.of("CP-3.1", "CP-3.2", "CP-3.3").contains(id));
			if (harassmentProfile == null && finding.harassmentAssessment() != null) {
				throw new InvalidContractException("Legacy findings must not carry harassmentAssessment");
			}
			if (harassmentProfile != null) {
				if (evaluated && finding.harassmentAssessment() == null) {
					throw new InvalidContractException("HARASSMENT evaluation requires harassmentAssessment");
				}
				if (finding.harassmentAssessment() != null) {
					finding.harassmentAssessment().validateProfile(harassmentProfile);
					if (finding.harassmentAssessment().clauses().stream()
							.anyMatch(clause -> clause.verdict() == Verdict.NEEDS_REVIEW)
							&& finding.verdict() != Verdict.NEEDS_REVIEW) {
						throw new InvalidContractException("Unresolved harassment clauses require NEEDS_REVIEW");
					}
					boolean violation = finding.harassmentAssessment().clauses().stream()
							.anyMatch(clause -> clause.verdict() == Verdict.VIOLATION);
					if (violation != finding.categories().contains(Category.HARASSMENT)
							|| (violation && finding.verdict() == Verdict.NO_VIOLATION)) {
						throw new InvalidContractException("HARASSMENT category must match structured violation clauses");
					}
					for (var clause : finding.harassmentAssessment().clauses()) {
						if ((clause.verdict() == Verdict.VIOLATION) != finding.policyIds().contains(clause.policyId())) {
							throw new InvalidContractException("policyIds must match structured violation clauses");
						}
					}
				}
				if (harassmentProfile.level() == HarassmentLevel.RELAXED
						&& finding.policyIds().contains("CP-3.3")) {
					throw new InvalidContractException("RELAXED forbids CP-3.3");
				}
			}
		}
	}

	public record MessageFinding(
			UUID messageId,
			Verdict verdict,
			List<Category> categories,
			List<String> policyIds,
			String reason,
			List<String> missingFacts,
			@JsonInclude(JsonInclude.Include.NON_NULL) HarassmentAssessment harassmentAssessment) {
		public MessageFinding(UUID messageId, Verdict verdict, List<Category> categories, List<String> policyIds,
				String reason, List<String> missingFacts) {
			this(messageId, verdict, categories, policyIds, reason, missingFacts, null);
		}
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
