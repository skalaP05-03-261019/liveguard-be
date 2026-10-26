package com.lifeguards.liveguard.contracts.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.enums.HarassmentLevel;
import com.lifeguards.liveguard.contracts.enums.Verdict;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record HarassmentAssessment(List<Clause> clauses) {
	public enum RequirementStatus { MET, NOT_MET, UNKNOWN }
	public enum ExceptionStatus { NOT_APPLICABLE, APPLIES, DOES_NOT_APPLY, UNKNOWN }

	public HarassmentAssessment {
		clauses = DtoValidation.immutableList(clauses, "clauses");
		DtoValidation.requireNotEmpty(clauses, "clauses");
		if (clauses.stream().map(Clause::policyId).distinct().count() != clauses.size()) {
			throw new InvalidContractException("clauses must not repeat");
		}
	}

	public void validateProfile(HarassmentProfile profile) {
		Set<String> expected = profile.level() == HarassmentLevel.RELAXED
				? Set.of("CP-3.2") : Set.of("CP-3.2", "CP-3.3");
		if (!expected.equals(clauses.stream().map(Clause::policyId).collect(java.util.stream.Collectors.toSet()))) {
			throw new InvalidContractException("clauses must evaluate exactly the applicable harassment clauses");
		}
		for (Clause clause : clauses) {
			if ((profile.level() == HarassmentLevel.STRICT)
					!= (clause.exceptionStatus() == ExceptionStatus.NOT_APPLICABLE)) {
				throw new InvalidContractException("CP-2.2 is NOT_APPLICABLE only in STRICT");
			}
		}
	}

	public record Clause(String policyId, Verdict verdict, RequirementStatus targetStatus,
			RequirementStatus conductStatus, List<UUID> targetEvidenceMessageIds,
			List<UUID> conductEvidenceMessageIds, ExceptionStatus exceptionStatus,
			List<UUID> exceptionEvidenceMessageIds) {
		public Clause {
			if (!Set.of("CP-3.2", "CP-3.3").contains(DtoValidation.requireText(policyId, "policyId"))) {
				throw new InvalidContractException("Only CP-3.2 and CP-3.3 are harassment violation clauses");
			}
			DtoValidation.requireNonNull(verdict, "verdict");
			DtoValidation.requireNonNull(targetStatus, "targetStatus");
			DtoValidation.requireNonNull(conductStatus, "conductStatus");
			targetEvidenceMessageIds = DtoValidation.immutableList(targetEvidenceMessageIds, "targetEvidenceMessageIds");
			conductEvidenceMessageIds = DtoValidation.immutableList(conductEvidenceMessageIds, "conductEvidenceMessageIds");
			DtoValidation.requireNonNull(exceptionStatus, "exceptionStatus");
			exceptionEvidenceMessageIds = DtoValidation.immutableList(exceptionEvidenceMessageIds,
					"exceptionEvidenceMessageIds");
			if (exceptionStatus == ExceptionStatus.APPLIES) {
				DtoValidation.requireNotEmpty(exceptionEvidenceMessageIds, "exceptionEvidenceMessageIds");
			}
			if (exceptionStatus == ExceptionStatus.NOT_APPLICABLE && !exceptionEvidenceMessageIds.isEmpty()) {
				throw new InvalidContractException("Excluded exceptions must not carry exception evidence");
			}
			if (targetStatus == RequirementStatus.MET) {
				DtoValidation.requireNotEmpty(targetEvidenceMessageIds, "targetEvidenceMessageIds");
			}
			if (conductStatus == RequirementStatus.MET) {
				DtoValidation.requireNotEmpty(conductEvidenceMessageIds, "conductEvidenceMessageIds");
			}
			if (verdict == Verdict.VIOLATION
					&& (targetStatus != RequirementStatus.MET || conductStatus != RequirementStatus.MET)) {
				throw new InvalidContractException("Harassment violations require both target and conduct evidence");
			}
			if (verdict == Verdict.VIOLATION && exceptionStatus != ExceptionStatus.NOT_APPLICABLE
					&& exceptionStatus != ExceptionStatus.DOES_NOT_APPLY) {
				throw new InvalidContractException("A violation requires resolved, non-applicable exceptions");
			}
			Verdict expected;
			if (targetStatus == RequirementStatus.NOT_MET || conductStatus == RequirementStatus.NOT_MET
					|| exceptionStatus == ExceptionStatus.APPLIES) {
				expected = Verdict.NO_VIOLATION;
			} else if (targetStatus == RequirementStatus.MET && conductStatus == RequirementStatus.MET
					&& exceptionStatus != ExceptionStatus.UNKNOWN) {
				expected = Verdict.VIOLATION;
			} else {
				expected = Verdict.NEEDS_REVIEW;
			}
			if (verdict != expected) {
				throw new InvalidContractException("Clause verdict must match requirement and exception states");
			}
		}
	}
}
