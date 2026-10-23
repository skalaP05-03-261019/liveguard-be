package com.lifeguards.liveguard.guard.outbox;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;

public record AgentTaskEnvelope(UUID eventId, UUID taskId, UUID caseId, Kind kind, String threadId,
		UUID snapshotId, int expectedEvidenceVersion, UUID decisionId, ReviewKind reviewKind,
		UUID reviewId, UUID traceId, @JsonInclude(JsonInclude.Include.NON_NULL) UUID liveId) {

	/** Retains the wire shape of envelopes persisted before liveId was introduced. */
	public AgentTaskEnvelope(UUID eventId, UUID taskId, UUID caseId, Kind kind, String threadId,
			UUID snapshotId, int expectedEvidenceVersion, UUID decisionId, ReviewKind reviewKind,
			UUID reviewId, UUID traceId) {
		this(eventId, taskId, caseId, kind, threadId, snapshotId, expectedEvidenceVersion,
				decisionId, reviewKind, reviewId, traceId, null);
	}

	public enum Kind {
		ANALYZE, REANALYZE, RESUME_REVIEW
	}

	public enum ReviewKind {
		CONTEXT, KICK
	}

	public AgentTaskEnvelope {
		DtoValidation.requireNonNull(eventId, "eventId");
		DtoValidation.requireNonNull(taskId, "taskId");
		DtoValidation.requireNonNull(caseId, "caseId");
		DtoValidation.requireNonNull(kind, "kind");
		threadId = DtoValidation.requireText(threadId, "threadId");
		DtoValidation.requireNonNull(snapshotId, "snapshotId");
		DtoValidation.requireNonNull(traceId, "traceId");
		if (expectedEvidenceVersion < 1) {
			throw new InvalidContractException("expectedEvidenceVersion must be positive");
		}
		if (kind == Kind.RESUME_REVIEW) {
			DtoValidation.requireNonNull(decisionId, "decisionId");
			DtoValidation.requireNonNull(reviewKind, "reviewKind");
			DtoValidation.requireNonNull(reviewId, "reviewId");
		} else if (decisionId != null || reviewKind != null || reviewId != null) {
			throw new InvalidContractException("Only RESUME_REVIEW may reference a review decision");
		}
	}

	public String operationId() {
		return kind == Kind.RESUME_REVIEW ? "resume:" + decisionId : "run:" + taskId;
	}

	public String endpoint() {
		return "/agent/tasks/" + taskId + (kind == Kind.RESUME_REVIEW ? "/resume" : "/run");
	}
}
