package com.lifeguards.liveguard.internal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lifeguards.liveguard.contracts.dto.DtoValidation;

// Agent's KICK contract differs from the existing internal RECONCILE_REVIEW command.
public record BusinessActionCommand(
		UUID actionId,
		UUID planId,
		UUID caseId,
		Type type,
		UUID targetId,
		int expectedEvidenceVersion,
		int expectedTargetVersion,
		String policyVersion,
		String idempotencyKey,
		Integer durationSeconds,
		UUID decisionId,
		List<UUID> violationIds) {

	public enum Type {
		HIDE, TIMEOUT, RELEASE_HELD, KICK
	}

	public BusinessActionCommand {
		Objects.requireNonNull(actionId, "actionId");
		Objects.requireNonNull(planId, "planId");
		Objects.requireNonNull(caseId, "caseId");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(targetId, "targetId");
		if (expectedEvidenceVersion <= 0) {
			throw new IllegalArgumentException("expectedEvidenceVersion must be positive");
		}
		if (expectedTargetVersion <= 0) {
			throw new IllegalArgumentException("expectedTargetVersion must be positive");
		}
		policyVersion = DtoValidation.requireText(policyVersion, "policyVersion");
		idempotencyKey = DtoValidation.requireText(idempotencyKey, "idempotencyKey");
		if (idempotencyKey.length() > 200) {
			throw new IllegalArgumentException("idempotencyKey must be at most 200 characters");
		}
		violationIds = violationIds == null ? List.of() : List.copyOf(violationIds);
		if (type == Type.TIMEOUT) {
			if (durationSeconds == null || durationSeconds < 1) {
				throw new IllegalArgumentException("TIMEOUT requires positive durationSeconds");
			}
		} else if (durationSeconds != null) {
			throw new IllegalArgumentException("durationSeconds is only valid for TIMEOUT");
		}
		if (type == Type.KICK) {
			Objects.requireNonNull(decisionId, "KICK requires decisionId");
			if (violationIds.stream().distinct().count() < 3) {
				throw new IllegalArgumentException("KICK requires at least three distinct violationIds");
			}
		} else if (decisionId != null || !violationIds.isEmpty()) {
			throw new IllegalArgumentException("decisionId and violationIds are only valid for KICK");
		}
	}
}
